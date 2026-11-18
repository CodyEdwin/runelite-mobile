package net.kdt.pojavlaunch.runelite.sound;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Control;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.Line;
import javax.sound.sampled.LineListener;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;

/** SourceDataLine backed by an AAudio output stream (librlaudio.so). */
final class AAudioLine implements SourceDataLine {
    private static Boolean loaded;
    private final AndroidMixer mixer;
    private AudioFormat format;
    private int bufferBytes;
    private long stream;
    private volatile boolean running;
    private long framesFlushed;

    static synchronized boolean supportedOnDevice() {
        if (loaded == null) {
            try {
                String dir = System.getProperty("runelite.android.nativeDir");
                if (dir != null) System.load(dir + "/librlaudio.so"); else System.loadLibrary("rlaudio");
                loaded = nInit();
            } catch (Throwable t) {
                System.err.println("AAudioLine: " + t);
                loaded = false;
            }
            System.out.println("AAudioLine: AAudio output " + (loaded ? "available" : "unavailable"));
        }
        return loaded;
    }

    AAudioLine(AndroidMixer mixer, AudioFormat format, int bufferBytes) {
        this.mixer = mixer;
        this.format = format;
        this.bufferBytes = bufferBytes;
    }

    @Override public void open() throws LineUnavailableException { open(format, bufferBytes); }
    @Override public void open(AudioFormat f) throws LineUnavailableException { open(f, AudioSystem.NOT_SPECIFIED); }

    @Override
    public synchronized void open(AudioFormat f, int bytes) throws LineUnavailableException {
        if (stream != 0) return;
        if (!AndroidMixer.supported(f)) throw new LineUnavailableException("unsupported format " + f);
        int rate = f.getSampleRate() > 0 ? Math.round(f.getSampleRate()) : 22050;
        if (f.getSampleRate() <= 0) f = new AudioFormat(rate, 16, f.getChannels(), true, false);
        int frameSize = f.getFrameSize();
        if (bytes <= 0) bytes = rate / 2 * frameSize;
        bytes -= bytes % frameSize;
        stream = nOpen(rate, f.getChannels(), bytes / frameSize);
        if (stream == 0) throw new LineUnavailableException("AAudio could not open " + f);
        format = f;
        bufferBytes = bytes;
        framesFlushed = 0;
        System.out.println("AAudioLine: opened " + f + ", buffer " + bytes + " bytes");
    }

    @Override
    public synchronized void close() {
        if (stream != 0) nClose(stream);
        stream = 0;
        running = false;
        mixer.closed(this);
    }

    @Override public boolean isOpen() { return stream != 0; }

    @Override
    public synchronized void start() {
        if (stream != 0 && !running) { nStart(stream); running = true; }
    }

    @Override
    public synchronized void stop() {
        if (stream != 0 && running) { nPause(stream); running = false; }
    }

    @Override public boolean isRunning() { return running; }
    @Override public boolean isActive() { return running && stream != 0 && nQueued(stream) > 0; }

    @Override
    public void drain() {
        while (running && stream != 0 && nQueued(stream) > 0) {
            try { Thread.sleep(5); } catch (InterruptedException e) { return; }
        }
    }

    @Override
    public synchronized void flush() {
        if (stream == 0) return;
        boolean was = running;
        if (was) nPause(stream);
        nFlush(stream);
        if (was) nStart(stream);
    }

    @Override
    public int available() {
        long s = stream;
        if (s == 0) return 0;
        long free = bufferBytes - nQueued(s) * format.getFrameSize();
        return (int) Math.max(0, Math.min(bufferBytes, free));
    }

    @Override
    public int write(byte[] b, int off, int len) {
        int frameSize = format.getFrameSize();
        len -= len % frameSize;
        int done = 0;
        while (done < len) {
            long s = stream;
            if (s == 0) break;
            int r = nWrite(s, b, off + done, (len - done) / frameSize, frameSize, 100_000_000L);
            if (r < 0) break;
            done += r * frameSize;
            if (r == 0 && !running) break; // stopped and full: don't block forever
        }
        return done;
    }

    @Override public int getBufferSize() { return bufferBytes; }
    @Override public AudioFormat getFormat() { return format; }
    @Override public int getFramePosition() { return (int) getLongFramePosition(); }
    @Override public long getLongFramePosition() { long s = stream; return s == 0 ? 0 : nFramesRead(s); }
    @Override public long getMicrosecondPosition() { return (long) (getLongFramePosition() * 1e6 / format.getFrameRate()); }
    @Override public float getLevel() { return AudioSystem.NOT_SPECIFIED; }
    @Override public Line.Info getLineInfo() { return new DataLine.Info(SourceDataLine.class, format, bufferBytes); }
    @Override public Control[] getControls() { return new Control[0]; }
    @Override public boolean isControlSupported(Control.Type t) { return false; }
    @Override public Control getControl(Control.Type t) { throw new IllegalArgumentException("no control " + t); }
    @Override public void addLineListener(LineListener l) { }
    @Override public void removeLineListener(LineListener l) { }

    private static native boolean nInit();
    private static native long nOpen(int rate, int channels, int bufferFrames);
    private static native int nWrite(long h, byte[] data, int off, int frames, int frameSize, long timeoutNanos);
    private static native long nQueued(long h);
    private static native long nFramesRead(long h);
    private static native void nStart(long h);
    private static native void nPause(long h);
    private static native void nFlush(long h);
    private static native void nClose(long h);
}
