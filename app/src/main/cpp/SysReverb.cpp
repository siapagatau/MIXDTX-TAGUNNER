#include "SysReverb.h"
#include <algorithm>
#include <cmath>
#if defined(__SSE__)
#include <xmmintrin.h>
#endif

namespace {
// Flush-to-zero: ekor reverb yang meluruh ke angka super kecil (denormal)
// bisa bikin CPU melonjak saat musik berhenti. Dipasang di thread yang
// memanggil process() (murah: beberapa instruksi per blok).
inline void enableFlushToZero() {
#if defined(__aarch64__)
    uint64_t fpcr;
    __asm__ __volatile__("mrs %0, fpcr" : "=r"(fpcr));
    fpcr |= (1ULL << 24);   // bit FZ
    __asm__ __volatile__("msr fpcr, %0" : : "r"(fpcr));
#elif defined(__SSE__)
    _mm_setcsr(_mm_getcsr() | 0x8040);   // FTZ + DAZ
#endif
}
}  // namespace

SysReverb::SysReverb() {
    // Default send: bass kering, makin ke atas makin banyak.
    const float rev[kBands] = {0.0f, 0.3f, 0.8f, 1.0f};
    const float ech[kBands] = {0.0f, 0.0f, 0.7f, 0.7f};
    for (int i = 0; i < kBands; ++i) {
        revSend_[i].store(rev[i]);
        echoSend_[i].store(ech[i]);
    }
}

void SysReverb::Lp::set(float fc, float sampleRate) {
    const float q = 0.70710678f;
    const float w0 = 2.0f * (float) M_PI * fc / sampleRate;
    const float cs = std::cos(w0);
    const float alpha = std::sin(w0) / (2.0f * q);
    const float a0 = 1.0f + alpha;
    b0 = ((1.0f - cs) * 0.5f) / a0;
    b1 = (1.0f - cs) / a0;
    b2 = b0;
    a1 = (-2.0f * cs) / a0;
    a2 = (1.0f - alpha) / a0;
}

void SysReverb::prepare(int sampleRate) {
    sampleRate_ = sampleRate;
    lp_[0].set(90.0f, (float) sampleRate);
    lp_[1].set(400.0f, (float) sampleRate);
    lp_[2].set(4000.0f, (float) sampleRate);
    for (auto &f: lp_) f.reset();

    reverb_.prepare(sampleRate);
    reverb_.setDry(0.0f);        // wet-only: suara asli TIDAK ikut keluar dari sini
    reverb_.setEnabled(true);    // on/off diatur sendiri lewat revGate_ (lihat process)
    echo_.prepare(sampleRate);
    echo_.setEnabled(true);

    revGate_ = reverbOn_.load() ? 1.0f : 0.0f;
    echoGate_ = echoOn_.load() ? 1.0f : 0.0f;
    ms_.store(0.0f);
}

void SysReverb::setReverbParams(float wet, float width, float damp, float room, float cess, float preDelayMs) {
    reverb_.setWet(std::fmax(0.0f, std::fmin(1.5f, wet)));
    reverb_.setWidth(std::fmax(0.0f, std::fmin(1.0f, width)));
    reverb_.setDamp(std::fmax(0.0f, std::fmin(1.0f, damp)));
    reverb_.setRoomSize(std::fmax(0.0f, std::fmin(1.0f, room)));
    reverb_.setCess(std::fmax(0.0f, std::fmin(1.0f, cess)));
    reverb_.setPreDelayMs(preDelayMs);
}

void SysReverb::setEchoParams(float delayMs, float feedback, float mix) {
    echo_.setDelayMs(delayMs);
    echo_.setFeedback(feedback);
    echo_.setMix(mix);
}

void SysReverb::setSwitches(bool reverbOn, bool echoOn) {
    reverbOn_.store(reverbOn);
    echoOn_.store(echoOn);
}

void SysReverb::setBandSend(int band, float reverbSend, float echoSend) {
    if (band < 0 || band >= kBands) return;
    revSend_[band].store(std::fmax(0.0f, std::fmin(1.0f, reverbSend)));
    echoSend_[band].store(std::fmax(0.0f, std::fmin(1.0f, echoSend)));
}

void SysReverb::process(const int16_t *in, int16_t *out, int frames) {
    enableFlushToZero();

    float rs[kBands], es[kBands];
    for (int b = 0; b < kBands; ++b) {
        rs[b] = revSend_[b].load();
        es[b] = echoSend_[b].load();
    }
    const float gain = gain_.load();
    const float revTarget = reverbOn_.load() ? 1.0f : 0.0f;
    const float echoTarget = echoOn_.load() ? 1.0f : 0.0f;
    constexpr float kInv = 1.0f / 32768.0f;
    constexpr float kGateStep = 0.002f;   // ~10ms di 48kHz

    double sumSq = 0.0;
    for (int i = 0; i < frames; ++i) {
        const float l = (float) in[2 * i] * kInv;
        const float r = (float) in[2 * i + 1] * kInv;
        const float m = 0.5f * (l + r);

        // 4 band, jumlahnya = m persis.
        const float sub = lp_[0].process(m);
        const float rest1 = m - sub;
        const float low = lp_[1].process(rest1);
        const float rest2 = rest1 - low;
        const float mid = lp_[2].process(rest2);
        const float high = rest2 - mid;

        const float revIn = sub * rs[0] + low * rs[1] + mid * rs[2] + high * rs[3];
        const float echoIn = sub * es[0] + low * es[1] + mid * es[2] + high * es[3];

        float rl = 0.0f, rr = 0.0f, el = 0.0f, er = 0.0f;
        reverb_.process(revIn, rl, rr);
        echo_.process(echoIn, el, er);

        revGate_ += kGateStep * (revTarget - revGate_);
        echoGate_ += kGateStep * (echoTarget - echoGate_);

        float outL = (rl * revGate_ + el * echoGate_) * gain;
        float outR = (rr * revGate_ + er * echoGate_) * gain;
        outL = std::fmax(-1.0f, std::fmin(1.0f, outL));
        outR = std::fmax(-1.0f, std::fmin(1.0f, outR));

        out[2 * i] = (int16_t) std::lrintf(outL * 32767.0f);
        out[2 * i + 1] = (int16_t) std::lrintf(outR * 32767.0f);
        sumSq += 0.5 * ((double) outL * outL + (double) outR * outR);
    }
    if (frames > 0) ms_.store((float) (sumSq / frames));
}
