package org.noise_planet.qrtone;

import org.junit.Test;

import java.util.Arrays;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.*;

public class QRToneTest {

    @Test
    public void crcTest() {
        byte[] expectedPayload = new byte[]{18, 32, -117, -93, -50, 2, 52, 26, -117, 93, 119, -109, 39, 46, 108, 4, 31, 36, -100, 95, -9, -70, -82, -93, -75, -32, -63, 42, -44, -100, 50, 83, -118, 114};
        byte base = QRTone.crc8(expectedPayload, 0, expectedPayload.length);
        AtomicLong next = new AtomicLong(1337);
        for(int i=0; i < expectedPayload.length; i++) {
            byte[] alteredPayload = Arrays.copyOf(expectedPayload, expectedPayload.length);
            alteredPayload[i] = (byte) (QRTone.warbleRand(next) % 255);
            assertNotEquals(base, QRTone.crc8(alteredPayload, 0, alteredPayload.length));
        }
    }


    @Test
    public void generalized_goertzel() throws Exception {
        double sampleRate = 44100;
        double powerRMS = Math.pow(10, -26.0 / 20.0); // -26 dBFS
        float signalFrequency = 1000;
        double powerPeak = powerRMS * Math.sqrt(2);

        float[] audio = new float[4410];
        for (int s = 0; s < audio.length; s++) {
            double t = s * (1 / sampleRate);
            audio[s] = (float)(Math.cos(QRTone.M2PI * signalFrequency * t) * (powerPeak));
        }

        IterativeGeneralizedGoertzel.GoertzelResult res = new IterativeGeneralizedGoertzel(sampleRate, signalFrequency,
                audio.length).processSamples(audio).computeRMS(true);


        double signal_rms = QRTone.computeRms(audio);

        assertEquals(signal_rms, res.rms, 0.1);
        assertEquals(0, res.phase, 1e-8);
    }

    @Test
    public void generalized_goertzelIterativeTest() throws Exception {
        double sampleRate = 44100;
        double powerRMS = Math.pow(10, -26.0 / 20.0); // -26 dBFS
        float signalFrequency = 1000;
        double powerPeak = powerRMS * Math.sqrt(2);

        float[] audio = new float[44100];
        for (int s = 0; s < audio.length; s++) {
            double t = s * (1 / sampleRate);
            audio[s] = (float)(Math.cos(QRTone.M2PI * signalFrequency * t) * (powerPeak));
        }

        int cursor = 0;
        Random random = new Random(1337);
        IterativeGeneralizedGoertzel goertzel = new IterativeGeneralizedGoertzel(sampleRate, signalFrequency,
                audio.length);
        while (cursor < audio.length) {
            int windowSize = Math.min(random.nextInt(115) + 20, audio.length - cursor);
            float[] window = new float[windowSize];
            System.arraycopy(audio, cursor, window, 0, window.length);
            goertzel.processSamples(window);
            cursor += windowSize;
        }
        IterativeGeneralizedGoertzel.GoertzelResult res = goertzel.computeRMS(true);


        double signal_rms = QRTone.computeRms(audio);

        assertEquals(20 * Math.log10(signal_rms), 20 * Math.log10(res.rms), 0.1);
        assertEquals(0, res.phase, 1e-8);
    }

    public void printArray(double[] frequencies, double[]... arrays) {
        for (int idfreq = 0; idfreq < QRTone.NUM_FREQUENCIES; idfreq++) {
            if (idfreq > 0) {
                System.out.print(", ");
            }
            System.out.print(String.format(Locale.ROOT, "%.0f Hz", frequencies[idfreq]));
        }
        System.out.print("\n");
        for(int idarray = 0; idarray < arrays.length; idarray++) {
            for (int idfreq = 0; idfreq < QRTone.NUM_FREQUENCIES; idfreq++) {
                if (idfreq > 0) {
                    System.out.print(", ");
                }
                System.out.print(String.format(Locale.ROOT, "%.2f", 20 * Math.log10(arrays[idarray][idfreq])));
            }
            System.out.print("\n");
        }
    }

    @Test
    public void testGoertzelLeaks() {


        double sampleRate = 44100;
        double powerRMS = Math.pow(10, -26.0 / 20.0); // -26 dBFS
        double powerPeak = powerRMS * Math.sqrt(2);
        Configuration configuration = Configuration.getAudible(4, sampleRate);
        double[] frequencies = configuration.computeFrequencies(QRTone.NUM_FREQUENCIES);
        int signalFreqIndex = 1;

        float[] audio = new float[30000];
        double[] reference = new double[QRTone.NUM_FREQUENCIES];
        for (int s = 0; s < audio.length; s++) {
            double t = s * (1 / sampleRate);
            audio[s] = (float) (Math.cos(QRTone.M2PI * frequencies[signalFreqIndex] * t) * (powerPeak));
        }
        for (int idfreq = 0; idfreq < QRTone.NUM_FREQUENCIES; idfreq++) {
            IterativeGeneralizedGoertzel goertzel = new IterativeGeneralizedGoertzel(sampleRate, frequencies[idfreq], audio.length);
            goertzel.processSamples(audio);
            reference[idfreq] = goertzel.computeRMS(false).rms;
        }
        int windowSize = (int)(sampleRate*0.087/2);
        int s = 0;
        double rms[] = new double[QRTone.NUM_FREQUENCIES];
        IterativeGeneralizedGoertzel[] goertzel = new IterativeGeneralizedGoertzel[QRTone.NUM_FREQUENCIES];
        for (int idfreq = 0; idfreq < QRTone.NUM_FREQUENCIES; idfreq++) {
            goertzel[idfreq] = new IterativeGeneralizedGoertzel(sampleRate,frequencies[idfreq], windowSize);
        }
        int pushed = 0;
        while (s < audio.length - windowSize) {
            float[] window = Arrays.copyOfRange(audio, s, s + windowSize);
            QRTone.applyHamming(window, window.length, 0);
            for (int idfreq = 0; idfreq < QRTone.NUM_FREQUENCIES; idfreq++) {
                goertzel[idfreq].processSamples(window);
                rms[idfreq] += goertzel[idfreq].computeRMS(false).rms;
            }
            pushed += 1;
            s += windowSize;
        }
        for (int idfreq = 0; idfreq < QRTone.NUM_FREQUENCIES; idfreq++) {
            rms[idfreq] /= pushed;
        }

        double refSignal = 20*Math.log10(rms[signalFreqIndex]);

        // We need sufficient level decrease on neighbors frequencies
        assertTrue(49 < Math.abs(refSignal - 20*Math.log10(rms[signalFreqIndex - 1])));
        assertTrue(49 < Math.abs(refSignal - 20*Math.log10(rms[signalFreqIndex + 1])));

        // Print results
        printArray(frequencies, reference, rms);
    }

    @Test
    public void testTukeyWindow() {
        float[] expected = new float[]{0f,0.0157084f,0.0618467f,0.135516f,0.232087f,0.345492f,0.468605f,0.593691f,
                0.71289f,0.818712f,0.904508f,0.964888f,0.996057f,1f,1f,1f,1f,1f,1f,1f,1f,1f,1f,1f,1f,1f,1f,1f,1f,1f,
                1f,1f,1f,1f,1f,1f,1f,1f,0.996057f,0.964888f,0.904508f,0.818712f,0.71289f,0.593691f,0.468605f,0.345492f,
                0.232087f,0.135516f,0.0618467f,0.0157084f,0f
        };

        float[] window = new float[expected.length];
        Arrays.fill(window, 1.0f);

        QRTone.applyTukey(window, 0.5, window.length, 0);

        assertArrayEquals(expected, window, 1e-5f);
    }


    /**
     * Check equality between iterative window function on random size windows with full window Tukey
     *
     * @throws Exception
     */
    @Test
    public void iterativeTestTukey() throws Exception {
        double sampleRate = 44100;
        double powerRMS = Math.pow(10, -26.0 / 20.0); // -26 dBFS
        float signalFrequency = 1000;
        double powerPeak = powerRMS * Math.sqrt(2);

        float[] audio = new float[44100];
        for (int s = 0; s < audio.length; s++) {
            double t = s * (1 / sampleRate);
            audio[s] = (float) (Math.cos(QRTone.M2PI * signalFrequency * t) * (powerPeak));
        }

        int cursor = 0;
        Random random = new Random(1337);
        double iterativeRMS = 0;
        while (cursor < audio.length) {
            int windowSize = Math.min(random.nextInt(115) + 20, audio.length - cursor);
            float[] window = new float[windowSize];
            System.arraycopy(audio, cursor, window, 0, window.length);
            QRTone.applyTukey(window, 0.5, audio.length, cursor);
            for (float v : window) {
                iterativeRMS += v * v;
            }
            cursor += windowSize;
        }
        QRTone.applyTukey(audio, 0.5, audio.length, 0);
        double signal_rms = QRTone.computeRms(audio);
        assertEquals(signal_rms, Math.sqrt(iterativeRMS / audio.length), 1e-6);
    }
}