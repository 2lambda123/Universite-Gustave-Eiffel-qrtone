/*
 * BSD 3-Clause License
 *
 * Copyright (c) Unité Mixte de Recherche en Acoustique Environnementale (univ-gustave-eiffel)
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 *  Redistributions of source code must retain the above copyright notice, this
 *   list of conditions and the following disclaimer.
 *
 *  Redistributions in binary form must reproduce the above copyright notice,
 *   this list of conditions and the following disclaimer in the documentation
 *   and/or other materials provided with the distribution.
 *
 *  Neither the name of the copyright holder nor the names of its
 *   contributors may be used to endorse or promote products derived from
 *   this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 *
 */

package org.noise_planet.qrtone;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Analyse audio samples in order to detect trigger signal
 * Evaluate the exact position of the first tone
 */
public class TriggerAnalyzer {
    public static final double M2PI = Math.PI * 2;
    public static final double PERCENTILE_BACKGROUND = 0.5;
    private AtomicInteger processedWindowAlpha = new AtomicInteger(0);
    private AtomicInteger processedWindowBeta = new AtomicInteger(0);
    private final int windowOffset;
    private final int gateLength;
    private IterativeGeneralizedGoertzel[] frequencyAnalyzersAlpha;
    private IterativeGeneralizedGoertzel[] frequencyAnalyzersBeta;
    final ApproximatePercentile backgroundNoiseEvaluator;
    final CircularArray[] splHistory;
    private float[] hannWindowCache;
    final PeakFinder peakFinder;
    private final int windowAnalyze;
    private TriggerCallback triggerCallback = null;
    final double[] frequencies;
    final double sampleRate;
    public final double triggerSnr;
    private long firstToneLocation = -1;



    public TriggerAnalyzer(double sampleRate, int gateLength, double[] frequencies, int windowLength, double triggerSnr) {
        this.windowAnalyze = windowLength;
        this.frequencies = frequencies;
        this.sampleRate = sampleRate;
        this.triggerSnr = triggerSnr;
        this.gateLength = gateLength;
        if(windowAnalyze < Configuration.computeMinimumWindowSize(sampleRate, frequencies[0], frequencies[1])) {
            throw new IllegalArgumentException("Tone length are not compatible with sample rate and selected frequencies");
        }
        // 50% overlap
        windowOffset = windowAnalyze / 2;
        frequencyAnalyzersAlpha = new IterativeGeneralizedGoertzel[frequencies.length];
        frequencyAnalyzersBeta = new IterativeGeneralizedGoertzel[frequencies.length];
        backgroundNoiseEvaluator = new ApproximatePercentile(PERCENTILE_BACKGROUND);
        splHistory = new CircularArray[frequencies.length];
        peakFinder = new PeakFinder();
        peakFinder.setMinDecreaseCount((gateLength / 2) / windowOffset);
        hannWindowCache = new float[windowLength / 2 + 1];
        for(int i=0; i < hannWindowCache.length; i++) {
            hannWindowCache[i] = (float)(0.5 - 0.5 * Math.cos((M2PI * i) / (windowLength - 1)));
        }
        for(int i=0; i<frequencies.length; i++) {
            frequencyAnalyzersAlpha[i] = new IterativeGeneralizedGoertzel(sampleRate, frequencies[i], windowLength, false);
            frequencyAnalyzersBeta[i] = new IterativeGeneralizedGoertzel(sampleRate, frequencies[i], windowLength, false);
            splHistory[i] = new CircularArray((gateLength * 3) / windowOffset);
        }
    }

    public void reset() {
        firstToneLocation = -1;
        peakFinder.reset();
        processedWindowAlpha.set(0);
        processedWindowBeta.set(0);
        for(int i=0; i<frequencies.length; i++) {
            frequencyAnalyzersAlpha[i].reset();
            frequencyAnalyzersBeta[i].reset();
            splHistory[i].clear();
        }
    }

    public void setTriggerCallback(TriggerCallback triggerCallback) {
        this.triggerCallback = triggerCallback;
    }

    public long getFirstToneLocation() {
        return firstToneLocation;
    }

    private void doProcess(float[] samples, long totalProcessed, AtomicInteger windowProcessed,
                           IterativeGeneralizedGoertzel[] frequencyAnalyzers) {
        int processed = 0;
        while(firstToneLocation == -1 && processed < samples.length) {
            int toProcess = Math.min(samples.length - processed,windowAnalyze - windowProcessed.get());
            // Apply Hann window
            for(int i=0; i < toProcess; i++) {
                final float hann = i + windowProcessed.get() < hannWindowCache.length ? hannWindowCache[i + windowProcessed.get()] : hannWindowCache[(windowAnalyze - 1) - (i + windowProcessed.get())];
                samples[i+processed] *= hann;
            }
            for(int idfreq = 0; idfreq < frequencyAnalyzers.length; idfreq++) {
                frequencyAnalyzers[idfreq].processSamples(samples, processed, processed + toProcess);
            }
            processed += toProcess;
            windowProcessed.addAndGet(toProcess);
            if(windowProcessed.get() == windowAnalyze) {
                windowProcessed.set(0);
                double[] splLevels = new double[frequencies.length];
                for(int idfreq = 0; idfreq < frequencies.length; idfreq++) {
                    double splLevel = 20 * Math.log10(frequencyAnalyzers[idfreq].
                            computeRMS(false).rms);
                    splLevels[idfreq] = splLevel;
                    if(idfreq == frequencies.length - 1) {
                        backgroundNoiseEvaluator.add(splLevel);
                    }
                    splHistory[idfreq].add((float)splLevel);
                }
                final long location = totalProcessed + processed - windowAnalyze;
                if(peakFinder.add(location, splHistory[frequencies.length - 1].last())) {
                    // Find peak
                    PeakFinder.Element element = peakFinder.getLastPeak();
                    // Check if peak value is greater than specified Signal Noise ratio
                    double backgroundNoiseSecondPeak = backgroundNoiseEvaluator.result();
                    if(element.value > backgroundNoiseSecondPeak + triggerSnr) {
                        // Check if the level on other triggering frequencies is below triggering level (at the same time)
                        int peakIndex = splHistory[frequencies.length - 1].size() - 1 -
                                (int)(location / windowOffset - element.index / windowOffset);
                        if(peakIndex >= 0 && peakIndex < splHistory[0].size() &&
                                splHistory[0].get(peakIndex) < element.value - triggerSnr) {
                            int firstPeakIndex = peakIndex - (gateLength / windowOffset);
                            // Check if for the first peak the level was inferior than trigger level
                            if(firstPeakIndex >= 0 && firstPeakIndex < splHistory[0].size()
                                    && splHistory[0].get(firstPeakIndex) > element.value - triggerSnr &&
                                    splHistory[frequencies.length - 1].get(firstPeakIndex) < element.value - triggerSnr) {
                                // All trigger conditions are met
                                // Evaluate the exact position of the first tone
                                long peakLocation = findPeakLocation(splHistory[frequencies.length - 1].get(peakIndex-1)
                                        ,element.value,splHistory[frequencies.length - 1].get(peakIndex+1),element.index,windowOffset);
                                firstToneLocation = peakLocation + gateLength / 2 + windowOffset;
                                if(triggerCallback != null) {
                                    triggerCallback.onTrigger(this, firstToneLocation);
                                }
                            }
                        }
                    }
                }
                if(triggerCallback != null) {
                    triggerCallback.onNewLevels(this, location, splLevels);
                }
            }
        }
    }

    /**
     * @return Maximum window length in order to have not more than 1 processed window
     */
    public int getMaximumWindowLength() {
        return Math.min(windowAnalyze - processedWindowAlpha.get(), windowAnalyze - processedWindowBeta.get());
    }

    public void processSamples(float[] samples, long totalProcessed) {
        doProcess(Arrays.copyOf(samples, samples.length), totalProcessed, processedWindowAlpha, frequencyAnalyzersAlpha);
        if(totalProcessed > windowOffset) {
            doProcess(Arrays.copyOf(samples, samples.length), totalProcessed, processedWindowBeta, frequencyAnalyzersBeta);
        } else if(windowOffset - totalProcessed < samples.length){
            // Start to process on the part used by the offset window
            doProcess(Arrays.copyOfRange(samples, (int)(windowOffset - totalProcessed),
                    samples.length), totalProcessed + (int)(windowOffset - totalProcessed), processedWindowBeta,
                    frequencyAnalyzersBeta);
        }
    }

    /**
     * Quadratic interpolation of three adjacent samples
     * @param p0 y value of left point
     * @param p1 y value of center point (maximum height)
     * @param p2 y value of right point
     * @return location [-1; 1] relative to center point, height and half-curvature of a parabolic fit through
     * @link https://www.dsprelated.com/freebooks/sasp/Sinusoidal_Peak_Interpolation.html
     * three points
     */
     static double[] quadraticInterpolation(double p0, double p1, double p2) {
        double location;
        double height;
        double halfCurvature;
        location = (p2 - p0) / (2.0 * (2 * p1 - p2 - p0));
        height = p1 - 0.25 * (p0 - p2) * location;
        halfCurvature = 0.5 * (p0 - 2 * p1 + p2);
        return new double[]{location, height, halfCurvature};
    }

    /**
     * Evaluate peak location of a gaussian
     * @param p0 y value of left point
     * @param p1 y value of center point (maximum height)
     * @param p2 y value of right point
     * @param p1Location x value of p1
     * @param windowLength x delta between points
     * @return Peak x value
     */
    public static long findPeakLocation(double p0, double p1, double p2, long p1Location, int windowLength) {
        double location = quadraticInterpolation(p0, p1, p2)[0];
        return p1Location + (int)(location*windowLength);
    }

    public interface TriggerCallback {
        void onNewLevels(TriggerAnalyzer triggerAnalyzer, long location, double[] spl);
        void onTrigger(TriggerAnalyzer triggerAnalyzer, long messageStartLocation);
    }
}
