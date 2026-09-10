package se.bitcraze.crazyfliecontrol2;

import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint;
import org.opencv.core.Point;
import org.opencv.core.Rect;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;
import org.opencv.imgproc.Moments;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Tầng xử lý thị giác máy tính nhận diện màu sắc bằng OpenCV.
 * Port 1:1 từ tools/color_detect.py
 */
public class ColorDetector {

    public static class HsvRange {
        public final Scalar lower;
        public final Scalar upper;

        public HsvRange(double h1, double s1, double v1, double h2, double s2, double v2) {
            this.lower = new Scalar(h1, s1, v1);
            this.upper = new Scalar(h2, s2, v2);
        }
    }

    public static class ColorProfile {
        public final String name;
        public final List<HsvRange> ranges;
        public final float minAreaFrac;
        public final int maxBlobs;

        public ColorProfile(String name, List<HsvRange> ranges, float minAreaFrac, int maxBlobs) {
            this.name = name;
            this.ranges = ranges;
            this.minAreaFrac = minAreaFrac;
            this.maxBlobs = maxBlobs;
        }
    }

    public static class Detection {
        public final String label;
        public final int cx;
        public final int cy;
        public final float nx;
        public final float ny;
        public final double areaPx;
        public final float areaFrac;
        public final Rect bbox;

        public Detection(String label, int cx, int cy, float nx, float ny,
                         double areaPx, float areaFrac, Rect bbox) {
            this.label = label;
            this.cx = cx;
            this.cy = cy;
            this.nx = nx;
            this.ny = ny;
            this.areaPx = areaPx;
            this.areaFrac = areaFrac;
            this.bbox = bbox;
        }
    }

    private final List<ColorProfile> mProfiles;
    private final Map<String, Boolean> mEnabled;
    private int mBlurKsize = 5;
    private int mMorphKsize = 5;

    public ColorDetector() {
        mProfiles = createDefaultProfiles();
        mEnabled = new HashMap<String, Boolean>();
        for (ColorProfile prof : mProfiles) {
            mEnabled.put(prof.name, true);
        }
    }

    public static List<ColorProfile> createDefaultProfiles() {
        List<ColorProfile> list = new ArrayList<ColorProfile>();

        // 1. Red (2 dải do vắt qua mốc 0 của Hue)
        list.add(new ColorProfile("red", Arrays.asList(
                new HsvRange(0, 120, 70, 10, 255, 255),
                new HsvRange(170, 120, 70, 180, 255, 255)
        ), 0.002f, 5));

        // 2. Orange
        list.add(new ColorProfile("orange", Collections.singletonList(
                new HsvRange(11, 100, 80, 25, 255, 255)
        ), 0.002f, 5));

        // 3. Yellow
        list.add(new ColorProfile("yellow", Collections.singletonList(
                new HsvRange(26, 90, 90, 34, 255, 255)
        ), 0.002f, 5));

        // 4. Green
        list.add(new ColorProfile("green", Collections.singletonList(
                new HsvRange(35, 80, 60, 77, 255, 255)
        ), 0.002f, 5));

        // 5. Blue
        list.add(new ColorProfile("blue", Collections.singletonList(
                new HsvRange(100, 80, 60, 124, 255, 255)
        ), 0.002f, 5));

        // 6. Purple
        list.add(new ColorProfile("purple", Collections.singletonList(
                new HsvRange(125, 70, 60, 155, 255, 255)
        ), 0.002f, 5));

        return list;
    }

    public void setColorEnabled(String colorName, boolean enabled) {
        mEnabled.put(colorName, enabled);
    }

    public boolean isColorEnabled(String colorName) {
        Boolean b = mEnabled.get(colorName);
        return b != null && b;
    }

    public List<ColorProfile> getProfiles() {
        return mProfiles;
    }

    public Mat buildMask(Mat frameBgr, ColorProfile profile) {
        Mat img = frameBgr;
        Mat blur = null;
        if (mBlurKsize >= 3) {
            int k = mBlurKsize | 1;
            blur = new Mat();
            Imgproc.GaussianBlur(img, blur, new Size(k, k), 0);
            img = blur;
        }

        Mat hsv = new Mat();
        Imgproc.cvtColor(img, hsv, Imgproc.COLOR_BGR2HSV);
        if (blur != null) {
            blur.release();
        }

        Mat mask = new Mat();
        boolean first = true;
        for (HsvRange range : profile.ranges) {
            Mat part = new Mat();
            Core.inRange(hsv, range.lower, range.upper, part);
            if (first) {
                part.copyTo(mask);
                first = false;
            } else {
                Core.bitwise_or(mask, part, mask);
            }
            part.release();
        }
        hsv.release();

        if (mMorphKsize >= 3) {
            Mat kernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, new Size(mMorphKsize, mMorphKsize));
            Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_OPEN, kernel);
            Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_CLOSE, kernel);
            kernel.release();
        }
        return mask;
    }

    public List<Detection> detect(Mat frameBgr) {
        List<Detection> out = new ArrayList<Detection>();
        if (frameBgr == null || frameBgr.empty()) {
            return out;
        }
        int h = frameBgr.rows();
        int w = frameBgr.cols();
        double total = (double) (w * h);
        double halfW = w / 2.0;
        double halfH = h / 2.0;

        for (ColorProfile prof : mProfiles) {
            Boolean isEnabled = mEnabled.get(prof.name);
            if (isEnabled != null && !isEnabled) {
                continue;
            }

            Mat mask = buildMask(frameBgr, prof);
            List<MatOfPoint> contours = new ArrayList<MatOfPoint>();
            Mat hierarchy = new Mat();
            Imgproc.findContours(mask, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE);
            mask.release();
            hierarchy.release();

            List<Detection> found = new ArrayList<Detection>();
            for (MatOfPoint c : contours) {
                double area = Imgproc.contourArea(c);
                if (area / total < prof.minAreaFrac) {
                    c.release();
                    continue;
                }
                Moments m = Imgproc.moments(c);
                if (m.m00 <= 0.0) {
                    c.release();
                    continue;
                }
                int cx = (int) (m.m10 / m.m00);
                int cy = (int) (m.m01 / m.m00);
                float nx = (float) ((cx - halfW) / halfW);
                float ny = (float) ((cy - halfH) / halfH);
                float areaFrac = (float) (area / total);
                Rect bbox = Imgproc.boundingRect(c);
                c.release();

                found.add(new Detection(prof.name, cx, cy, nx, ny, area, areaFrac, bbox));
            }

            Collections.sort(found, new Comparator<Detection>() {
                @Override
                public int compare(Detection o1, Detection o2) {
                    return Double.compare(o2.areaPx, o1.areaPx);
                }
            });

            int limit = Math.min(found.size(), prof.maxBlobs);
            for (int i = 0; i < limit; i++) {
                out.add(found.get(i));
            }
        }

        Collections.sort(out, new Comparator<Detection>() {
            @Override
            public int compare(Detection o1, Detection o2) {
                return Double.compare(o2.areaPx, o1.areaPx);
            }
        });

        return out;
    }

    public void draw(Mat frameBgr, List<Detection> detections) {
        if (frameBgr == null || frameBgr.empty()) return;
        int h = frameBgr.rows();
        int w = frameBgr.cols();

        // Crosshair vạch tâm (xám đậm)
        Scalar gray = new Scalar(60, 60, 60);
        Imgproc.line(frameBgr, new Point(w / 2, 0), new Point(w / 2, h), gray, 1);
        Imgproc.line(frameBgr, new Point(0, h / 2), new Point(w, h / 2), gray, 1);

        Scalar yellow = new Scalar(0, 255, 255); // BGR yellow
        Scalar red = new Scalar(0, 0, 255);     // BGR red

        if (detections != null) {
            for (Detection d : detections) {
                // Viền khung chữ nhật vàng quanh vật thể
                Imgproc.rectangle(frameBgr, new Point(d.bbox.x, d.bbox.y),
                        new Point(d.bbox.x + d.bbox.width, d.bbox.y + d.bbox.height),
                        yellow, 2);

                // Chấm tròn đỏ tại tâm vật thể
                Imgproc.circle(frameBgr, new Point(d.cx, d.cy), 4, red, -1);

                // Nhãn hiển thị màu sắc và tọa độ chuẩn hóa nx, ny
                String label = String.format(Locale.US, "%s %+.2f,%+.2f", d.label, d.nx, d.ny);
                int textY = Math.max(d.bbox.y - 6, 14);
                Imgproc.putText(frameBgr, label, new Point(d.bbox.x, textY),
                        Imgproc.FONT_HERSHEY_SIMPLEX, 0.45, yellow, 1, Imgproc.LINE_AA);
            }
        }
    }
}
