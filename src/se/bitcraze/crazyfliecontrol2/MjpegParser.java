package se.bitcraze.crazyfliecontrol2;

import java.util.ArrayList;
import java.util.List;

/**
 * Parser tách các khung hình JPEG từ luồng byte MJPEG dựa trên SOI (0xFF, 0xD8) và EOI (0xFF, 0xD9).
 * Chuyển đổi 1:1 từ MjpegParser trong Python (camera_stream.py).
 */
public class MjpegParser {
    public static final int MAX_BUFFER_BYTES = 2 * 1024 * 1024; // 2MB
    public static final int MIN_JPEG_BYTES = 128;

    private byte[] mBuf;
    private int mSize;
    private int mResyncs;
    private int mFramesFound;

    public MjpegParser() {
        this(MAX_BUFFER_BYTES);
    }

    public MjpegParser(int maxBuffer) {
        mBuf = new byte[65536];
        mSize = 0;
        mResyncs = 0;
        mFramesFound = 0;
    }

    private void ensureCapacity(int needed) {
        if (mBuf.length < needed) {
            int newCap = Math.max(mBuf.length * 2, needed);
            byte[] newBuf = new byte[newCap];
            System.arraycopy(mBuf, 0, newBuf, 0, mSize);
            mBuf = newBuf;
        }
    }

    private int findMarker(int b1, int b2, int startIndex) {
        int limit = mSize - 1;
        for (int i = startIndex; i < limit; i++) {
            if ((mBuf[i] & 0xFF) == b1 && (mBuf[i + 1] & 0xFF) == b2) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Nạp chunk byte và trích xuất danh sách các frame JPEG hoàn chỉnh.
     */
    public synchronized List<byte[]> feed(byte[] chunk, int offset, int length) {
        List<byte[]> out = new ArrayList<>();
        if (chunk != null && length > 0) {
            ensureCapacity(mSize + length);
            System.arraycopy(chunk, offset, mBuf, mSize, length);
            mSize += length;
        }

        while (true) {
            // Tìm SOI: 0xFF, 0xD8
            int start = findMarker(0xFF, 0xD8, 0);
            if (start < 0) {
                // Chưa có SOI. Giữ lại 1 byte cuối phòng khi 0xFF nằm ở ranh giới chunk
                if (mSize > 1) {
                    mBuf[0] = mBuf[mSize - 1];
                    mSize = 1;
                }
                break;
            }

            // Tìm EOI: 0xFF, 0xD9 (bắt đầu tìm từ sau SOI)
            int end = findMarker(0xFF, 0xD9, start + 2);
            if (end < 0) {
                // Đã thấy đầu ảnh (SOI), nhưng chưa thấy đuôi (EOI)
                // Loại bỏ rác trước SOI để giải phóng bộ đệm
                if (start > 0) {
                    System.arraycopy(mBuf, start, mBuf, 0, mSize - start);
                    mSize -= start;
                }
                break;
            }

            // Đã có trọn vẹn 1 frame JPEG từ start đến end + 2
            int jpegLen = (end + 2) - start;
            if (jpegLen >= MIN_JPEG_BYTES) {
                byte[] jpeg = new byte[jpegLen];
                System.arraycopy(mBuf, start, jpeg, 0, jpegLen);
                out.add(jpeg);
                mFramesFound++;
            }

            // Cắt frame này ra khỏi buffer
            int nextStart = end + 2;
            int remaining = mSize - nextStart;
            if (remaining > 0) {
                System.arraycopy(mBuf, nextStart, mBuf, 0, remaining);
            }
            mSize = remaining;
        }

        // Chống tràn bộ đệm
        if (mSize > MAX_BUFFER_BYTES) {
            mSize = 0;
            mResyncs++;
        }

        return out;
    }

    public synchronized void reset() {
        mSize = 0;
    }

    public int getResyncs() {
        return mResyncs;
    }

    public int getFramesFound() {
        return mFramesFound;
    }
}
