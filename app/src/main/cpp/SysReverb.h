#pragma once
#include <atomic>
#include <cstdint>
#include "KaraokeEngine.h"   // FreeverbStyle + EchoEffect (dipakai ulang, tidak ada kode reverb ganda)

// REVERB + DELAY UNTUK AUDIO SISTEM (musik/YouTube/TikTok dll).
//
// Beda dengan jalur mic (KaraokeEngine), di sini sinyal masuk dari
// AudioPlaybackCapture (Kotlin, SysReverbService) lewat process(), dan yang
// KELUAR HANYA EFEKNYA (wet-only: ekor reverb + gema echo). Suara asli tetap
// diputar app sumbernya sendiri, jadi tidak ada suara ganda/dobel.
//
// Per band: sinyal dipecah jadi 4 band (Sub <90Hz, Low 90-400Hz, Mid 400-4kHz,
// High >4kHz - SAMA dengan batas band EQ Audio global). Tiap band punya knob
// "send" ke reverb dan "send" ke echo. Semua send dijumlahkan ke SATU reverb
// dan SATU echo (hemat CPU, tidak perlu 4 reverb). Contoh: reverb hanya di
// High (kilau), echo hanya di Mid (vokal), bass tetap kering.
//
// Semua parameter atomic -> aman diubah dari thread UI kapan pun.
class SysReverb {
public:
    static constexpr int kBands = 4;

    // Alokasi semua buffer (reverb, echo) + reset state. WAJIB dipanggil
    // sebelum thread audio mulai memanggil process(), BUKAN saat berjalan.
    void prepare(int sampleRate);

    // wet 0..1.5 | width, damp, room, cess 0..1 | preDelayMs 0..80
    void setReverbParams(float wet, float width, float damp, float room, float cess, float preDelayMs);
    // delayMs 50..600 | feedback 0..0.75 | mix 0..1
    void setEchoParams(float delayMs, float feedback, float mix);
    void setSwitches(bool reverbOn, bool echoOn);
    // band 0..3 (Sub, Low, Mid, High), send 0..1
    void setBandSend(int band, float reverbSend, float echoSend);
    // 0..2 (1 = apa adanya). Hanya mengalikan keluaran efek.
    void setGain(float g) { gain_.store(g); }

    // in/out: stereo interleaved int16, jumlah frame sama. out = EFEK SAJA.
    void process(const int16_t *in, int16_t *out, int frames);

    float getMeanSquare() const { return ms_.load(std::memory_order_relaxed); }

private:
    // Low-pass biquad orde 2 (Butterworth). Band dibentuk dengan pengurangan
    // komplementer (band atas = sisa sinyal - low-pass), jadi jumlah semua
    // band = sinyal asli persis, tanpa lubang/bukit di titik silang.
    struct Lp {
        float b0 = 1, b1 = 0, b2 = 0, a1 = 0, a2 = 0;
        float x1 = 0, x2 = 0, y1 = 0, y2 = 0;
        void set(float fc, float sampleRate);
        void reset() { x1 = x2 = y1 = y2 = 0; }
        float process(float in) {
            const float out = b0 * in + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2;
            x2 = x1; x1 = in;
            y2 = y1; y1 = out;
            return out;
        }
    };

    Lp lp_[kBands - 1];
    FreeverbStyle reverb_;
    EchoEffect echo_;
    int sampleRate_ = 48000;

    std::atomic<float> revSend_[kBands];
    std::atomic<float> echoSend_[kBands];
    std::atomic<float> gain_{1.0f};
    std::atomic<bool> reverbOn_{true};
    std::atomic<bool> echoOn_{false};
    std::atomic<float> ms_{0.0f};

    // Penghalus on/off (hanya disentuh audio thread) biar tidak "klik".
    float revGate_ = 1.0f;
    float echoGate_ = 0.0f;

public:
    SysReverb();
};
