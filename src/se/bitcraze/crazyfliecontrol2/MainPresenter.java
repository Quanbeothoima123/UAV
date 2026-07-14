package se.bitcraze.crazyfliecontrol2;

import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import se.bitcraze.crazyflie.lib.crazyflie.ConnectionAdapter;
import se.bitcraze.crazyflie.lib.crazyflie.Crazyflie;
import se.bitcraze.crazyflie.lib.crazyradio.ConnectionData;
import se.bitcraze.crazyflie.lib.crazyradio.RadioDriver;
import se.bitcraze.crazyflie.lib.crtp.CommanderPacket;
import se.bitcraze.crazyflie.lib.crtp.CrtpDriver;
import se.bitcraze.crazyflie.lib.crtp.CrtpPacket;
import se.bitcraze.crazyflie.lib.crtp.ZDistancePacket;
import se.bitcraze.crazyflie.lib.log.LogAdapter;
import se.bitcraze.crazyflie.lib.log.LogConfig;
import se.bitcraze.crazyflie.lib.log.Logg;
import se.bitcraze.crazyflie.lib.param.Param;
import se.bitcraze.crazyflie.lib.param.ParamListener;
import se.bitcraze.crazyflie.lib.toc.Toc;
import se.bitcraze.crazyflie.lib.toc.VariableType;
import se.bitcraze.crazyfliecontrol.ble.BleLink;
import se.bitcraze.crazyfliecontrol.console.ConsoleListener;
import se.bitcraze.crazyfliecontrol.controller.AbstractController;
import se.bitcraze.crazyfliecontrol.controller.Controls;
import se.bitcraze.crazyfliecontrol.controller.GamepadController;
import se.bitcraze.crazyfliecontrol.controller.IController;

public class MainPresenter {

    private static final String LOG_TAG = "Crazyflie-MainPresenter";
    // The vertical Y/T stick adjusts the altitude target, not raw throttle.
    // At 100% deflection it changes at most 5 cm every 0.5 second.
    private static final long UAV_ALTITUDE_REPEAT_MS = 500L;
    private static final float UAV_ALTITUDE_STEP_METERS = 0.05f;
    private static final float UAV_ALTITUDE_MIN_METERS = 0.0f;
    private static final float UAV_ALTITUDE_MAX_METERS = 2.0f;

    // Defaults from the current Python Auto-brake panel.
    private static final float UAV_BRAKE_K = 0.9f;
    private static final float UAV_BRAKE_RATIO = 0.8f;
    private static final long UAV_BRAKE_MIN_MS = 100L;
    private static final long UAV_BRAKE_MAX_MS = 1000L;
    private static final float UAV_AXIS_ACTIVE_EPSILON = 0.01f;

    private static final Pattern STATUS_ARM = Pattern.compile("(?:^|\\s)ARM=(\\d+)");
    private static final Pattern STATUS_THR = Pattern.compile("(?:^|\\s)THR=(-?\\d+)");
    private static final Pattern STATUS_VALID = Pattern.compile("(?:valid|Val)=(\\d+)");
    private static final Pattern STATUS_ALT = Pattern.compile("ALTm=([-\\d.]+)");
    private static final Pattern STATUS_VZ = Pattern.compile("VZ=([-\\d.]+)");
    private static final Pattern STATUS_TGT = Pattern.compile("TGT=([-\\d.]+)");
    private static final Pattern STATUS_MODE = Pattern.compile("MODE=(\\d+)");
    private static final Pattern STATUS_AV = Pattern.compile("AV=(\\d+)");
    private static final Pattern STATUS_TOK = Pattern.compile("TOK=(\\d+)");
    private static final Pattern STATUS_TERR = Pattern.compile("TERR=(\\d+)");
    private static final Pattern STATUS_MOTORS = Pattern.compile(
            "M\\s*=\\s*(-?\\d+) (-?\\d+) (-?\\d+) (-?\\d+)");
    private static final Pattern ALT_TARGET_REPLY = Pattern.compile("ALT TGT=([-\\d.]+)");

    private MainActivity mainActivity;

    private Crazyflie mCrazyflie;
    private CrtpDriver mDriver;
    private UavUdpLink mUavUdpLink;
    private volatile boolean mUavUdpConnecting;

    private Logg mLogg;
    private LogConfig mDefaultLogConfig = null;

    private Toc mLogToc;
    private Toc mParamToc;

    private boolean mHeadlightToggle = false;
    private boolean mSoundToggle = false;
    private int mRingEffect = 0;
    private int mNoRingEffect = 0;
    private int mCpuFlash = 0;
    private boolean isZrangerAvailable = false;
    private boolean heightHold = false;

    private Thread mSendJoystickDataThread;
    private ConsoleListener mConsoleListener;
    private volatile float mUavAltitudeTarget = Float.NaN;
    private volatile boolean mUavAutoBraking;

    private static class UavBrakeAxis {
        private int heldSign;
        private long holdStartMs;
        private float brakeCommand;
        private long brakeUntilMs;

        boolean isNewPilotInput(float pilotCommand) {
            if (Math.abs(pilotCommand) < UAV_AXIS_ACTIVE_EPSILON) {
                return false;
            }
            int sign = pilotCommand > 0.0f ? 1 : -1;
            return heldSign == 0 || heldSign != sign;
        }

        void cancelBrake() {
            brakeCommand = 0.0f;
            brakeUntilMs = 0L;
        }

        float update(float pilotCommand, long nowMs, float tiltStep) {
            if (Math.abs(pilotCommand) >= UAV_AXIS_ACTIVE_EPSILON) {
                int sign = pilotCommand > 0.0f ? 1 : -1;
                if (heldSign != sign) {
                    heldSign = sign;
                    holdStartMs = nowMs;
                }
                return pilotCommand;
            }

            if (heldSign != 0) {
                long holdDurationMs = Math.max(0L, nowMs - holdStartMs);
                long brakeDurationMs = Math.max(UAV_BRAKE_MIN_MS,
                        Math.min(UAV_BRAKE_MAX_MS,
                                Math.round(UAV_BRAKE_K * holdDurationMs)));
                brakeCommand = -heldSign * tiltStep * UAV_BRAKE_RATIO;
                brakeUntilMs = nowMs + brakeDurationMs;
                heldSign = 0;
            }

            if (nowMs < brakeUntilMs) {
                return brakeCommand;
            }
            cancelBrake();
            return 0.0f;
        }

        boolean isBraking(long nowMs) {
            return nowMs < brakeUntilMs && Math.abs(brakeCommand) > 0.0f;
        }
    }

    public MainPresenter(MainActivity mainActivity) {
        this.mainActivity = mainActivity;
    }

    public void onDestroy() {
        this.mainActivity = null;
    }

    private final UavUdpLink.Listener uavUdpListener = new UavUdpLink.Listener() {
        @Override
        public void onConnected(String host, int port) {
            mUavUdpConnecting = false;
            mUavAltitudeTarget = Float.NaN;
            mUavAutoBraking = false;
            if (mainActivity == null) {
                return;
            }
            mainActivity.showToastie("Đã kết nối UDP tới " + host + ":" + port);
            mainActivity.setConnectionButtonConnected();
            mainActivity.setLinkQualityText("UDP");
            mainActivity.setUavActionButtonsEnabled(true);
            mainActivity.updateUavStatus(
                    "Thiết bị: ĐANG CHỜ DỮ LIỆU\n"
                            + "Kết nối: Đã kết nối UDP tới " + host + ":" + port,
                    false);
            startUavControlThread();
            requestUavConfigurationSnapshot();
        }

        @Override
        public void onDisconnected() {
            mUavUdpConnecting = false;
            mUavAltitudeTarget = Float.NaN;
            mUavAutoBraking = false;
            stopSendJoystickDataThread();
            if (mainActivity == null) {
                return;
            }
            mainActivity.showToastie("Đã ngắt kết nối UDP");
            mainActivity.setConnectionButtonDisconnected();
            mainActivity.setUavActionButtonsEnabled(false);
            mainActivity.setLinkQualityText("N/A");
            mainActivity.updateUavStatus(
                    "Thiết bị: CHƯA SẴN SÀNG\nKết nối: Đã ngắt", false);
        }

        @Override
        public void onMessage(String line) {
            if (mainActivity != null) {
                // STATUS arrives very quickly. Render it in the fixed status
                // panel and keep the scrolling console for ACK/errors only.
                if (!handleUavTelemetry(line)) {
                    mainActivity.appendToConsole(line);
                }
            }
        }

        @Override
        public void onError(String message) {
            mUavUdpConnecting = false;
            if (mainActivity != null) {
                mainActivity.appendToConsole("[UDP] " + message);
                mainActivity.showToastie(message);
            }
        }
    };

    private ConnectionAdapter crazyflieConnectionAdapter = new ConnectionAdapter() {

        @Override
        public void connectionRequested() {
            mainActivity.showToastie("Connecting ...");
        }

        @Override
        public void connected() {
            mainActivity.showToastie("Connected");
            if (mCrazyflie == null) {
                mainActivity.setConnectionButtonConnected();
                return;
            }
            CrtpDriver driver = mCrazyflie.getDriver();
            if (driver instanceof BleLink) {
                // FIXME: Hack to circumvent BLE reconnect problem
                mainActivity.setConnectionButtonConnectedBle();
                mCrazyflie.startConnectionSetup_BLE();
            } else if (driver instanceof EspUdpDriver) {
                mainActivity.setConnectionButtonConnected();
                mCrazyflie.startConnectionSetup_BLE();
            } else {
                mainActivity.setConnectionButtonConnected();
            }
        }

        @Override
        public void setupFinished() {
            Param param = mCrazyflie.getParam();
            if (param != null) {
                final Toc paramToc = param.getToc();
                if (paramToc != null) {
                    mParamToc = paramToc;
                    mainActivity.showToastie("Parameters TOC fetch finished: " + paramToc.getTocSize());
                    checkForBuzzerDeck();
                    checkForNoOfRingEffects();
                    checkForZRanger();
                }
            }
            mLogg = mCrazyflie.getLogg();
            if (mLogg != null) {
                final Toc logToc = mLogg.getToc();
                if (logToc != null) {
                    mLogToc = logToc;
                    mainActivity.showToastie("Log TOC fetch finished: " + logToc.getTocSize());
                    mDefaultLogConfig = createDefaultLogConfig();
                    startLogConfigs(mDefaultLogConfig);
                }
            }
            startSendJoystickDataThread();
        }

        @Override
        public void connectionLost(final String msg) {
            mainActivity.showToastie(msg);
            mainActivity.setConnectionButtonDisconnected();
            disconnect();
        }

        @Override
        public void connectionFailed(final String msg) {
            mainActivity.showToastie(msg);
            disconnect();
        }

        @Override
        public void disconnected() {
            mainActivity.showToastie("Disconnected");
            mainActivity.setConnectionButtonDisconnected();
            mainActivity.disableButtonsAndResetBatteryLevel();
            stopLogConfigs(mDefaultLogConfig);
        }

        @Override
        public void linkQualityUpdated(final int quality) {
            mainActivity.setLinkQualityText(quality + "%");
        }
    };

    // TODO: Replace with specific test for buzzer deck
    private void checkForBuzzerDeck() {
        //activate buzzer sound button when a CF2 is recognized (a buzzer can not yet be detected separately)
        mCrazyflie.getParam().addParamListener(new ParamListener("cpu", "flash") {
            @Override
            public void updated(String name, Number value) {
                mCpuFlash = mCrazyflie.getParam().getValue("cpu.flash").intValue();
                //enable buzzer action button when a CF2 is found (cpu.flash == 1024)
                if (mCpuFlash == 1024) {
                    mainActivity.setBuzzerSoundButtonEnablement(true);
                }
                Log.d(LOG_TAG, "CPU flash: " + mCpuFlash);
            }
        });
        mCrazyflie.getParam().requestParamUpdate("cpu.flash");
    }

    private void checkForZRanger() {
        //this should return true when either a zRanger or a flow deck is connected
        mCrazyflie.getParam().addParamListener(new ParamListener("deck", "bcZRanger") {
            @Override
            public void updated(String name, Number value) {
                isZrangerAvailable = mCrazyflie.getParam().getValue("deck.bcZRanger").intValue() == 1;
                // TODO: indicate in the UI that the zRanger sensor is installed
                if (isZrangerAvailable) {
                    mainActivity.showToastie("Found zRanger sensor.");
                }
                Log.d(LOG_TAG, "is zRanger installed: " + isZrangerAvailable);
            }
        });
        mCrazyflie.getParam().requestParamUpdate("deck.bcZRanger");
    }

    private void checkForNoOfRingEffects() {
        //set number of LED ring effects
        mCrazyflie.getParam().addParamListener(new ParamListener("ring", "neffect") {
            @Override
            public void updated(String name, Number value) {
                mNoRingEffect = mCrazyflie.getParam().getValue("ring.neffect").intValue();
                //enable LED ring action buttons only when ring.neffect parameter is set correctly (=> hence it's a CF2 with a LED ring)
                if (mNoRingEffect > 0) {
                    mainActivity.setRingEffectButtonEnablement(true);
                    mainActivity.setHeadlightButtonEnablement(true);
                }
                Log.d(LOG_TAG, "No of ring effects: " + mNoRingEffect);
            }
        });
        mCrazyflie.getParam().requestParamUpdate("ring.neffect");
    }

    private void sendPacket(CrtpPacket packet) {
        if (mCrazyflie != null) {
            mCrazyflie.sendPacket(packet);
        }
    }

    /**
     * Start thread to periodically send commands containing the user input
     */
    private void startSendJoystickDataThread() {
        mSendJoystickDataThread = new Thread(new Runnable() {
            @Override
            public void run() {
                while (mainActivity != null && mCrazyflie != null) {
                    IController controller = mainActivity.getController();
                    if (controller == null) {
                        Log.d(LOG_TAG, "SendJoystickDataThread: controller is null.");
                        break;
                    }
                    float roll = controller.getRoll();
                    float pitch = controller.getPitch();
                    float yaw = controller.getYaw();
                    float thrustAbsolute = controller.getThrustAbsolute();
                    boolean xmode = mainActivity.getControls().isXmode();
                    if (heightHold) {
                        float targetHeight = controller.getTargetHeight();
                        sendPacket(new ZDistancePacket(roll, pitch, yaw, targetHeight));
                    } else {
                        sendPacket(new CommanderPacket(roll, pitch, yaw, (char) thrustAbsolute, xmode));
                    }
                    try {
                        Thread.sleep(20);
                    } catch (InterruptedException e) {
                        Log.d(LOG_TAG, "SendJoystickDataThread was interrupted.");
                        break;
                    }
                }
            }
        });
        mSendJoystickDataThread.start();
    }

    /**
     * Send the same text control protocol as uav_udp_console.py.
     * Roll/pitch signs follow the Python manual-control convention:
     * left/forward are positive joystick movements but roll right and pitch
     * forward are negative setpoints in the ESP32 firmware.
     */
    private void startUavControlThread() {
        stopSendJoystickDataThread();
        mSendJoystickDataThread = new Thread(new Runnable() {
            @Override
            public void run() {
                float lastRoll = Float.NaN;
                float lastPitch = Float.NaN;
                float lastYaw = Float.NaN;
                long lastSetpointTime = 0L;
                long lastAltitudeCommandTime = 0L;
                UavBrakeAxis rollBrake = new UavBrakeAxis();
                UavBrakeAxis pitchBrake = new UavBrakeAxis();
                int heldYawSign = 0;

                while (mainActivity != null && mUavUdpLink != null && mUavUdpLink.isConnected()) {
                    IController controller = mainActivity.getController();
                    if (controller == null) {
                        break;
                    }

                    float pilotRoll = -controller.getRoll();
                    float pilotPitch = -controller.getPitch();
                    // Current Python mapping: turn left is positive yaw-rate,
                    // turn right is negative.
                    float yaw = -controller.getYaw();
                    long now = System.currentTimeMillis();

                    boolean yawActive = Math.abs(yaw) >= UAV_AXIS_ACTIVE_EPSILON;
                    int yawSign = yawActive ? (yaw > 0.0f ? 1 : -1) : 0;
                    boolean newYawInput = yawSign != 0 && yawSign != heldYawSign;
                    boolean newDirectionalInput = rollBrake.isNewPilotInput(pilotRoll)
                            || pitchBrake.isNewPilotInput(pilotPitch)
                            || newYawInput;
                    // Python rule 1: any newly pressed direction cancels every
                    // brake that is currently running; the pilot always wins.
                    if (newDirectionalInput) {
                        rollBrake.cancelBrake();
                        pitchBrake.cancelBrake();
                    }
                    heldYawSign = yawSign;

                    // Python limits TILT step to 1..10 degrees. Keep the same
                    // guard even if legacy Android advanced settings are higher.
                    float tiltStep = Math.max(1.0f, Math.min(10.0f,
                            mainActivity.getControls().getRollPitchFactor()));
                    float roll = rollBrake.update(pilotRoll, now, tiltStep);
                    float pitch = pitchBrake.update(pilotPitch, now, tiltStep);
                    mUavAutoBraking = rollBrake.isBraking(now) || pitchBrake.isBraking(now);

                    boolean changed = Float.isNaN(lastRoll)
                            || Math.abs(roll - lastRoll) >= 0.01f
                            || Math.abs(pitch - lastPitch) >= 0.01f
                            || Math.abs(yaw - lastYaw) >= 0.01f;
                    if (changed || now - lastSetpointTime >= 1000L) {
                        sendUavCommand(String.format(Locale.US, "@SP SET %.2f %.2f %.2f", roll, pitch, yaw));
                        lastRoll = roll;
                        lastPitch = pitch;
                        lastYaw = yaw;
                        lastSetpointTime = now;
                    }

                    float throttleAxis = getUavThrottleAxis();
                    Controls controls = mainActivity.getControls();
                    float deadzone = controls.getDeadzone();
                    boolean throttleNeutral = Math.abs(throttleAxis) <= deadzone;
                    if (!throttleNeutral && !Float.isNaN(mUavAltitudeTarget)
                            && now - lastAltitudeCommandTime >= UAV_ALTITUDE_REPEAT_MS) {
                        float magnitude = Math.min(1.0f, Math.abs(throttleAxis));
                        float minPercent = controls.getMinThrust();
                        float maxPercent = controls.getMaxThrust();
                        // Same T percentage scale shown by FlightDataView.
                        float controlPercent = minPercent
                                + magnitude * Math.max(0.0f, maxPercent - minPercent);
                        float percentageFactor = Math.min(1.0f,
                                Math.max(0.0f, controlPercent / 100.0f));
                        float direction = throttleAxis > 0.0f ? 1.0f : -1.0f;
                        float deltaMeters = UAV_ALTITUDE_STEP_METERS
                                * percentageFactor * direction;
                        float newTarget = Math.max(UAV_ALTITUDE_MIN_METERS,
                                Math.min(UAV_ALTITUDE_MAX_METERS,
                                        mUavAltitudeTarget + deltaMeters));
                        if (Math.abs(newTarget - mUavAltitudeTarget) >= 0.0005f) {
                            mUavAltitudeTarget = newTarget;
                            sendUavCommand(String.format(
                                    Locale.US, "@ALT TGT %.3f", newTarget));
                        }
                        lastAltitudeCommandTime = now;
                    }

                    try {
                        Thread.sleep(100L);
                    } catch (InterruptedException e) {
                        break;
                    }
                }
            }
        }, "uav-control");
        mSendJoystickDataThread.start();
    }

    private float getUavThrottleAxis() {
        if (mainActivity == null || mainActivity.getControls() == null) {
            return 0.0f;
        }
        Controls controls = mainActivity.getControls();
        return (controls.getMode() == 1 || controls.getMode() == 3)
                ? controls.getRightAnalog_Y()
                : controls.getLeftAnalog_Y();
    }

    /**
     * Parse the fast STATUS telemetry into a stable Vietnamese summary.
     * Returns true when the line is high-rate telemetry and should not also be
     * appended to the scrolling console.
     */
    private boolean handleUavTelemetry(String line) {
        if (line == null) {
            return false;
        }

        Matcher targetReply = ALT_TARGET_REPLY.matcher(line);
        if (targetReply.find()) {
            mUavAltitudeTarget = parseFloat(targetReply.group(1), mUavAltitudeTarget);
        }

        Matcher armMatcher = STATUS_ARM.matcher(line);
        if (!armMatcher.find()) {
            return false;
        }

        String armed = armMatcher.group(1);
        String throttle = findValue(STATUS_THR, line, "?");
        String valid = findValue(STATUS_VALID, line, "0");
        String altitude = findValue(STATUS_ALT, line, "?");
        String verticalSpeed = findValue(STATUS_VZ, line, "?");
        String target = findValue(STATUS_TGT, line, null);
        String mode = findValue(STATUS_MODE, line, "?");
        String altitudeValid = findValue(STATUS_AV, line, null);
        String tofOk = findValue(STATUS_TOK, line, null);
        String tofError = findValue(STATUS_TERR, line, null);
        String motors = findMotorValues(line);

        if (target != null) {
            mUavAltitudeTarget = parseFloat(target, mUavAltitudeTarget);
        }

        boolean attitudeReady = "1".equals(valid);
        boolean hasAltitudeStatus = altitudeValid != null;
        boolean altitudeReady = !hasAltitudeStatus || ("1".equals(altitudeValid)
                && (tofOk == null || "1".equals(tofOk))
                && (tofError == null || "0".equals(tofError)));
        boolean ready = attitudeReady && altitudeReady;

        String status = "Thiết bị: " + (ready ? "SẴN SÀNG" : "CHƯA SẴN SÀNG")
                + "\nKết nối: UDP | ARM: " + ("1".equals(armed) ? "Đã bật" : "Chưa bật")
                + " | Ga firmware: " + throttle
                + "\nCân bằng: " + (attitudeReady ? "Tốt" : "Chưa hợp lệ")
                + " | Cảm biến cao: " + (altitudeReady ? "Sẵn sàng" : "Có lỗi")
                + "\nĐộ cao: " + altitude + " m | Mục tiêu: "
                + (target == null ? "?" : target) + " m | Vận tốc: " + verticalSpeed + " m/s"
                + "\nChế độ cao: " + altitudeModeName(mode)
                + " | Motor: " + motors
                + "\nPhanh ngang: " + (mUavAutoBraking ? "ĐANG PHANH" : "Sẵn sàng")
                + " | Cần cao: 0,5 giây/nhịp, tối đa 5 cm";
        if (mainActivity != null) {
            mainActivity.updateUavStatus(status, ready);
        }
        return true;
    }

    private String findValue(Pattern pattern, String line, String fallback) {
        Matcher matcher = pattern.matcher(line);
        return matcher.find() ? matcher.group(1) : fallback;
    }

    private String findMotorValues(String line) {
        Matcher matcher = STATUS_MOTORS.matcher(line);
        if (!matcher.find()) {
            return "?";
        }
        return matcher.group(1) + "/" + matcher.group(2) + "/"
                + matcher.group(3) + "/" + matcher.group(4);
    }

    private float parseFloat(String value, float fallback) {
        try {
            return Float.parseFloat(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private String altitudeModeName(String mode) {
        if ("0".equals(mode)) return "Tắt";
        if ("1".equals(mode)) return "Chỉ ghi log";
        if ("2".equals(mode)) return "Giữ độ cao";
        if ("3".equals(mode)) return "Đang cất cánh";
        if ("4".equals(mode)) return "Đang hạ cánh";
        return "Chưa rõ";
    }

    /**
     * Match the read-only configuration requests scheduled by the Python GUI
     * immediately after opening its UDP connection.
     */
    private void requestUavConfigurationSnapshot() {
        final UavUdpLink link = mUavUdpLink;
        if (link == null) {
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                String[] commands = {
                        "@PID GET", "@MAH GET", "@SP GET", "@TRIM GET",
                        "@ALT GET", "@TKO GET", "@LAND GET"
                };
                long[] delaysMs = {300L, 100L, 100L, 50L, 50L, 100L, 100L};
                for (int i = 0; i < commands.length; i++) {
                    try {
                        Thread.sleep(delaysMs[i]);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    if (mUavUdpLink != link || !link.isConnected()) {
                        return;
                    }
                    link.sendCommand(commands[i]);
                }
            }
        }, "uav-config-get").start();
    }

    private void stopSendJoystickDataThread() {
        if (mSendJoystickDataThread != null) {
            mSendJoystickDataThread.interrupt();
            mSendJoystickDataThread = null;
        }
    }

    public void connectCrazyradio(int radioChannel, int radioDatarate, File mCacheDir) {
        Log.d(LOG_TAG, "connectCrazyradio()");
        // ensure previous link is disconnected
        disconnect();
        mDriver = null;
        try {
            mDriver = new RadioDriver(new UsbLinkAndroid(mainActivity));
        } catch (IllegalArgumentException e) {
            Log.d(LOG_TAG, e.getMessage());
            mainActivity.showToastie(e.getMessage());
        } catch (IOException e) {
            Log.e(LOG_TAG, e.getMessage());
            mainActivity.showToastie(e.getMessage());
        }
        connect(mCacheDir, new ConnectionData(radioChannel, radioDatarate));
    }

    public void connectUDP(String host, int port) {
        Log.d(LOG_TAG, "connectUDP(" + host + ":" + port + ")");
        disconnect();
        final UavUdpLink link = new UavUdpLink(uavUdpListener);
        final String targetHost = host;
        final int targetPort = port;
        mUavUdpLink = link;
        mUavUdpConnecting = true;
            mainActivity.showToastie("Đang mở UDP tới " + targetHost + ":" + targetPort + " ...");

        // DNS lookup, socket creation and the first datagram must not run on
        // Android's UI thread (NetworkOnMainThreadException on real devices).
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    if (mUavUdpLink != link) {
                        return;
                    }
                    link.connect(targetHost, targetPort);
                } catch (IllegalArgumentException e) {
                    handleUavConnectFailure(link, e.getMessage());
                } catch (IOException e) {
                    handleUavConnectFailure(link, "Cannot open UDP link: " + e.getMessage());
                } catch (RuntimeException e) {
                    handleUavConnectFailure(link, "UDP connection failed: " + e.getMessage());
                }
            }
        }, "uav-udp-connect").start();
    }

    private void handleUavConnectFailure(UavUdpLink link, String message) {
        mUavUdpConnecting = false;
        if (mUavUdpLink == link) {
            mUavUdpLink = null;
        }
        link.disconnect();
        if (mainActivity != null) {
            mainActivity.showToastie(message == null ? "UDP connection failed" : message);
            mainActivity.setConnectionButtonDisconnected();
            mainActivity.setUavActionButtonsEnabled(false);
            mainActivity.setLinkQualityText("N/A");
        }
    }

    public void connectBle(boolean writeWithResponse, File mCacheDir) {
        Log.d(LOG_TAG, "connectBle()");
        // ensure previous link is disconnected
        disconnect();
        mDriver = null;
        mDriver = new BleLink(mainActivity, writeWithResponse);
        connect(mCacheDir, null);
    }

    private void connect(File mCacheDir, ConnectionData connectionData) {
        if (mDriver != null) {
            // add listener for connection status
            mDriver.addConnectionListener(crazyflieConnectionAdapter);

            mCrazyflie = new Crazyflie(mDriver, mCacheDir);
            if (mDriver instanceof RadioDriver) {
                mCrazyflie.setConnectionData(connectionData);
            }
            // connect
            mCrazyflie.connect();

            // add console listener
            if (mCrazyflie != null) {
                mConsoleListener = new ConsoleListener();
                mConsoleListener.setMainActivity(mainActivity);
                mCrazyflie.addDataListener(mConsoleListener);
            }
        } else {
            mainActivity.showToastie("Cannot connect: Crazyradio not attached and Bluetooth LE not available");
        }
    }

    public void disconnect() {
        Log.d(LOG_TAG, "disconnect()");
        mUavUdpConnecting = false;
        stopSendJoystickDataThread();

        if (mUavUdpLink != null) {
            UavUdpLink link = mUavUdpLink;
            mUavUdpLink = null;
            // Match UavUdpConsole.disconnect(): close the socket without
            // injecting an additional flight command.
            link.disconnect();
        }

        if (mCrazyflie != null) {
            mCrazyflie.removeDataListener(mConsoleListener);
            mCrazyflie.disconnect();
            mCrazyflie = null;
        }

        if (mDriver != null) {
            mDriver.removeConnectionListener(crazyflieConnectionAdapter);
        }

        // link quality is not available when there is no active connection
        mainActivity.setLinkQualityText("N/A");
    }

    public boolean isConnected() {
        return mUavUdpConnecting
                || (mUavUdpLink != null && mUavUdpLink.isConnected())
                || (mCrazyflie != null && mCrazyflie.isConnected());
    }

    public void sendUavCommand(String command) {
        if (mUavUdpLink != null && mUavUdpLink.isConnected()) {
            mUavUdpLink.sendCommand(command);
        }
    }

    public void armUav() {
        // Python ARM sends only 'r'. Entering flight mode ('f') remains a
        // separate explicit action via the FLIGHT button.
        sendUavCommand("r");
    }

    public void enterFlightModeUav() {
        sendUavCommand("f");
    }

    public void killUav() {
        sendUavCommand("k");
    }

    public void takeoffUav() {
        sendUavCommand("@ALT TAKEOFF");
    }

    public void landUav() {
        sendUavCommand("l");
    }

    public void enableAltHoldMode(boolean hover) {
        // For safety reasons, altHold mode is only supported when the Crazyradio and a game pad are used
        if (mCrazyflie != null && mCrazyflie.getDriver() instanceof RadioDriver && mainActivity.getController() instanceof GamepadController) {
            if (isZrangerAvailable) {
                heightHold = hover;
                // reset target height, when hover is deactivated
                if (!hover) {
                    ((GamepadController) mainActivity.getController()).setTargetHeight(AbstractController.INITIAL_TARGET_HEIGHT);
                }
            } else {
//                Log.i(LOG_TAG, "flightmode.althold: getThrust(): " + mController.getThrustAbsolute());
                mCrazyflie.setParamValue("flightmode.althold", hover ? 1 : 0);
            }
        }
    }

    //TODO: make runAltAction more universal
    public void runAltAction(String action) {
        Log.i(LOG_TAG, "runAltAction: " + action);
        if (mCrazyflie != null) {
            if ("ring.headlightEnable".equalsIgnoreCase(action)) {
                // Toggle LED ring headlight
                mHeadlightToggle = !mHeadlightToggle;
                mCrazyflie.setParamValue(action, mHeadlightToggle ? 1 : 0);
                mainActivity.toggleHeadlightButtonColor(mHeadlightToggle);
            } else if ("ring.effect".equalsIgnoreCase(action)) {
                // Cycle through LED ring effects
                Log.i(LOG_TAG, "Ring effect: " + mRingEffect);
                mCrazyflie.setParamValue(action, mRingEffect);
                mRingEffect++;
                mRingEffect = (mRingEffect > mNoRingEffect) ? 0 : mRingEffect;
            } else if (action.startsWith("sound.effect")) {
                // Toggle buzzer deck sound effect
                String[] split = action.split(":");
                Log.i(LOG_TAG, "Sound effect: " + split[1]);
                mCrazyflie.setParamValue(split[0], mSoundToggle ? Integer.parseInt(split[1]) : 0);
                mSoundToggle = !mSoundToggle;
            }
        } else {
            Log.d(LOG_TAG, "runAltAction - crazyflie is null");
        }
    }

    public Crazyflie getCrazyflie() {
        return mCrazyflie;
    }

    private LogAdapter standardLogAdapter = new LogAdapter() {

        public void logDataReceived(LogConfig logConfig, Map<String, Number> data, int timestamp) {
            super.logDataReceived(logConfig, data, timestamp);

            if ("Standard".equals(logConfig.getName())) {
                final float battery = (float) data.get("pm.vbat");
                mainActivity.setBatteryLevel(battery);
            }
            for (Map.Entry<String, Number> entry : data.entrySet()) {
                Log.d(LOG_TAG, "\t Name: " + entry.getKey() + ", data: " + entry.getValue());
            }
        }

    };

    private LogConfig createDefaultLogConfig() {
        LogConfig logConfigStandard = new LogConfig("Standard", 1000);
        logConfigStandard.addVariable("pm.vbat", VariableType.FLOAT);
        return logConfigStandard;
    }

    /**
     * Start logging config
     */
    private void startLogConfigs(LogConfig logConfig) {
        if (mLogg == null) {
            Log.e(LOG_TAG, "startLogConfigs: mLogg was null!!");
            return;
        }
        if (logConfig == null) {
            Log.e(LOG_TAG, "startLogConfigs: Logg was null!!");
            return;
        }
        mLogg.addLogListener(standardLogAdapter);
        mLogg.addConfig(logConfig);
        mLogg.start(logConfig);
    }

    /**
     * Stop logging config
     */
    private void stopLogConfigs(LogConfig logConfig) {
        if (mLogg == null) {
            Log.e(LOG_TAG, "stopLogConfigs: mLogg was null!!");
            return;
        }
        if (logConfig == null) {
            Log.e(LOG_TAG, "stopLogConfigs: Logg was null!!");
            return;
        }
        mLogg.stop(logConfig);
        mLogg.delete(logConfig);
        mLogg.removeLogListener(standardLogAdapter);
    }
}
