package com.codyedwin.runelitemobile.shim.sound;

import javax.sound.sampled.Mixer;
import javax.sound.sampled.spi.MixerProvider;

/** javax.sound.sampled provider for Android: the bundled JRE has no libjsound, so no other mixer works. */
public final class AndroidMixerProvider extends MixerProvider {
    @Override
    public Mixer.Info[] getMixerInfo() {
        return AAudioLine.supportedOnDevice() ? new Mixer.Info[]{AndroidMixer.INFO} : new Mixer.Info[0];
    }

    @Override
    public Mixer getMixer(Mixer.Info info) {
        if (AndroidMixer.INFO.equals(info) || (info == null && AAudioLine.supportedOnDevice())) return AndroidMixer.INSTANCE;
        throw new IllegalArgumentException("no mixer " + info);
    }
}
