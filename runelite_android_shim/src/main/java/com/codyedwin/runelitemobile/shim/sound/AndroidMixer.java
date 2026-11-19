package com.codyedwin.runelitemobile.shim.sound;

import java.util.ArrayList;
import java.util.List;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Control;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.Line;
import javax.sound.sampled.LineListener;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.SourceDataLine;

/** Output-only mixer whose SourceDataLines play through Android AAudio. */
final class AndroidMixer implements Mixer {
    static final Mixer.Info INFO = new Mixer.Info("Android AAudio", "RuneLite Mobile", "AAudio output", "1.0") { };
    static final AndroidMixer INSTANCE = new AndroidMixer();
    private static final AudioFormat[] FORMATS = {
            new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, AudioSystem.NOT_SPECIFIED, 16, 1, 2, AudioSystem.NOT_SPECIFIED, false),
            new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, AudioSystem.NOT_SPECIFIED, 16, 2, 4, AudioSystem.NOT_SPECIFIED, false),
    };
    private static final DataLine.Info LINE_INFO = new DataLine.Info(SourceDataLine.class, FORMATS, AudioSystem.NOT_SPECIFIED, AudioSystem.NOT_SPECIFIED);
    private final List<Line> lines = new ArrayList<>();

    static boolean supported(AudioFormat f) {
        return f.getEncoding().equals(AudioFormat.Encoding.PCM_SIGNED) && f.getSampleSizeInBits() == 16 && !f.isBigEndian()
                && (f.getChannels() == 1 || f.getChannels() == 2);
    }

    @Override public Mixer.Info getMixerInfo() { return INFO; }
    @Override public Line.Info[] getSourceLineInfo() { return new Line.Info[]{LINE_INFO}; }
    @Override public Line.Info[] getTargetLineInfo() { return new Line.Info[0]; }
    @Override public Line.Info[] getSourceLineInfo(Line.Info info) { return isLineSupported(info) ? getSourceLineInfo() : new Line.Info[0]; }
    @Override public Line.Info[] getTargetLineInfo(Line.Info info) { return new Line.Info[0]; }

    @Override
    public boolean isLineSupported(Line.Info info) {
        if (info == null || !info.getLineClass().isAssignableFrom(SourceDataLine.class)) return false;
        if (info instanceof DataLine.Info) {
            AudioFormat[] fs = ((DataLine.Info) info).getFormats();
            if (fs.length == 0) return true;
            for (AudioFormat f : fs) if (supported(f)) return true;
            return false;
        }
        return true;
    }

    @Override
    public synchronized Line getLine(Line.Info info) throws LineUnavailableException {
        if (!isLineSupported(info)) throw new IllegalArgumentException("unsupported line " + info);
        AudioFormat format = new AudioFormat(22050, 16, 2, true, false);
        int buffer = AudioSystem.NOT_SPECIFIED;
        if (info instanceof DataLine.Info) {
            for (AudioFormat f : ((DataLine.Info) info).getFormats()) if (supported(f)) { format = f; break; }
            buffer = ((DataLine.Info) info).getMaxBufferSize();
        }
        AAudioLine line = new AAudioLine(this, format, buffer);
        lines.add(line);
        return line;
    }

    synchronized void closed(Line l) { lines.remove(l); }

    @Override public int getMaxLines(Line.Info info) { return isLineSupported(info) ? AudioSystem.NOT_SPECIFIED : 0; }
    @Override public synchronized Line[] getSourceLines() { return lines.toArray(new Line[0]); }
    @Override public Line[] getTargetLines() { return new Line[0]; }
    @Override public void synchronize(Line[] l, boolean m) { throw new IllegalArgumentException("not supported"); }
    @Override public void unsynchronize(Line[] l) { throw new IllegalArgumentException("not supported"); }
    @Override public boolean isSynchronizationSupported(Line[] l, boolean m) { return false; }
    @Override public Line.Info getLineInfo() { return new Line.Info(Mixer.class); }
    @Override public void open() { }
    @Override public void close() { }
    @Override public boolean isOpen() { return true; }
    @Override public Control[] getControls() { return new Control[0]; }
    @Override public boolean isControlSupported(Control.Type t) { return false; }
    @Override public Control getControl(Control.Type t) { throw new IllegalArgumentException("no control " + t); }
    @Override public void addLineListener(LineListener l) { }
    @Override public void removeLineListener(LineListener l) { }
}
