#pragma once
#include <oboe/Oboe.h>
#include <array>
#include <vector>
#include <atomic>
#include <memory>
#include <mutex>
#include <cmath>

// Reverb ringan bergaya Freeverb (comb + allpass), sama pendekatannya kayak
// SimpleReverb di proyek KENDANG GLER, tapi di sini parameternya kebuka penuh
// ke user (Wet, Dry, Width, Damp, Room Size) lewat knob di UI, bukan preset
// tetap. Delay line dialokasi sekali di prepare() (dipanggil sebelum stream
// jalan), BUKAN di audio callback, biar gak ada alokasi memori di real-time
// thread (itu bisa bikin glitch/xrun).
class FreeverbStyle {
public:
    void prepare(int sampleRate);

    // Parameter 0..1 semua, langsung dari knob UI:
    //  roomSize -> makin gede makin "membesar" ruangnya (feedback comb makin tinggi, ekor makin panjang)
    //  damp     -> makin gede makin "gelap"/teredam nada tingginya
    //  width    -> stereo spread hasil reverb (0 = mono, 1 = paling lebar)
    //  wet/dry  -> level sinyal basah/kering yang di-mix ke output
    void setRoomSize(float v);
    void setDamp(float v);
    void setWidth(float v);
    void setWet(float v) { wet_.store(v); }
    void setDry(float v) { dry_.store(v); }
    // cess -> 0..1, karakter "ssss" panjang ala reverb Alesis. Makin gede:
    // (1) nada tinggi (4-9kHz, tempat huruf S/C/T) makin banyak masuk ke
    // tangki reverb, (2) redaman nada tinggi di ekor makin kecil jadi
    // desisnya meluruh lama (cesss...), (3) filter penghalus ujung wet
    // makin terbuka. Cess = 0 -> reverb gelap/halus biasa.
    void setCess(float v);
    // preDelayMs -> jeda sebelum ekor reverb mulai "membuncah", 0..80ms.
    // Ini yang bikin reverb unit niatan (Alesis dkk) kedengeran rapi/jelas
    // dibanding reverb bawaan mixer murah: vokal kering kedengar dulu utuh,
    // BARU nyusul dengungnya - bukan nyampur langsung jadi "becek". Aman
    // dipanggil kapan aja, gak realokasi (buffer pre-delay dialokasi
    // sekali di prepare() sesuai kMaxPreDelayMs).
    void setPreDelayMs(float ms);

    // Bypass total efek reverb-nya (Wet komponen jadi 0, delay line comb
    // BERHENTI diproses - hemat CPU, bukan cuma di-mute doang). Suara mic
    // "kering" tetap keluar sesuai knob Dry, cuma dengungnya yang hilang.
    // Nilai knob (roomSize/damp/dst) TETAP kesimpen walau di-off-in, jadi
    // pas di-on-in lagi settingannya balik persis kayak sebelumnya.
    void setEnabled(bool e) { enabled_.store(e); }

    // input = 1 sample mono dari mic (frame ini), outL/outR = hasil MIX AKHIR
    // (dry + wet) yang siap ditulis ke buffer output stereo.
    //
    // PENTING soal delay: comb/allpass di bawah ini MEMANG punya "delay line"
    // internal, tapi itu cuma buat MEMBENTUK karakter ekor reverb-nya (gimana
    // dengungnya berkembang), BUKAN nunda sinyal input->output. Bagian "dry"
    // (dry_*input) ditambahkan LANGSUNG di frame yang sama, gak nunggu apa-apa.
    // Jadi suara asli tetep nyampe speaker secepat mungkin (nol delay
    // tambahan); yang "telat dikit-dikit" cuma ekor reverbnya doang, dan itu
    // memang sifat alami reverb - bukan bug/lag di app-nya.
    void process(float input, float &outL, float &outR);

private:
    struct Comb {
        std::vector<float> buffer;
        int index = 0;
        float feedback = 0.5f;
        float damp1 = 0.2f;
        float damp2 = 0.8f;
        float filterStore = 0.0f;
        void setSize(int size) {
            buffer.assign(size > 0 ? size : 1, 0.0f);
            index = 0;
            filterStore = 0.0f;
        }
        float process(float in) {
            float output = buffer[index];
            filterStore = (output * damp2) + (filterStore * damp1);
            buffer[index] = in + (filterStore * feedback);
            if (++index >= (int) buffer.size()) index = 0;
            return output;
        }
        // Sama seperti process(), tapi TITIK BACA-nya digeser sedikit ke
        // depan dari titik tulis (readOffset dalam sample, biasanya kecil
        // & goyang lewat LFO), diinterpolasi linear biar geserannya halus
        // (gak "klik"). Ini yang bikin karakter comb ini goyang halus
        // (chorus-y) alih-alih dengung statis - buffer sudah dialokasi
        // extra headroom di prepare() khusus buat comb yang pakai fungsi
        // ini, jadi aman gak kebaca sample yang belum sempat ditulis.
        float processModulated(float in, float readOffset) {
            const int size = (int) buffer.size();
            float readPos = (float) index + readOffset;
            int i0 = ((int) readPos) % size;
            if (i0 < 0) i0 += size;
            int i1 = i0 + 1;
            if (i1 >= size) i1 = 0;
            float frac = readPos - std::floor(readPos);
            float output = buffer[i0] * (1.0f - frac) + buffer[i1] * frac;
            filterStore = (output * damp2) + (filterStore * damp1);
            buffer[index] = in + (filterStore * feedback);
            if (++index >= size) index = 0;
            return output;
        }
    };
    struct Allpass {
        std::vector<float> buffer;
        int index = 0;
        float feedback = 0.5f;
        void setSize(int size) {
            buffer.assign(size > 0 ? size : 1, 0.0f);
            index = 0;
        }
        float process(float in) {
            float bufOut = buffer[index];
            float output = -in + bufOut;
            buffer[index] = in + (bufOut * feedback);
            if (++index >= (int) buffer.size()) index = 0;
            return output;
        }
    };

    static constexpr int kNumCombs = 8;
    static constexpr int kNumAllpasses = 4;
    // 4 dari 8 comb dimodulasi dikit (bukan semua) - biar tetep ada comb
    // yang "diam" jadi jangkar pitch, sementara yang lain goyang halus buat
    // shimmer. Modulasi di SEMUA comb sekaligus malah bikin "wobbly"/pitchy,
    // bukan lush.
    static constexpr int kNumModulatedCombs = 4;
    static constexpr float kMaxPreDelayMs = 80.0f;

    std::array<Comb, kNumCombs> combsL_;
    std::array<Comb, kNumCombs> combsR_;
    std::array<Allpass, kNumAllpasses> allpassL_;
    std::array<Allpass, kNumAllpasses> allpassR_;

    // Buffer pre-delay (mono, sebelum masuk ke tangki comb/allpass).
    std::vector<float> preDelayBuf_;
    int preDelayWrite_ = 0;
    std::atomic<int> preDelaySamples_{0};
    int sampleRate_ = 44100;

    // LFO modulasi halus buat 2 comb (kiri & kanan beda fase dikit biar
    // stereo-nya gerak, bukan mono-modulated). Depth-nya SENGAJA kecil
    // (dalam sample, bukan ms) - cukup buat "melembutkan" ringing metalik,
    // gak sampe kedengeran kayak efek chorus terpisah.
    float lfoPhaseL_ = 0.0f;
    float lfoPhaseR_ = 1.57f; // ~90 derajat offset dari L
    static constexpr float kLfoRateHz = 0.6f;
    static constexpr float kLfoDepthSamples = 3.0f;

    // Filter "air" di ujung sinyal wet (one-pole low-pass). Dulu koefisiennya
    // KONSTAN 0.35 (low-pass ~3kHz) -> semua desis "s" di ekor reverb
    // terpotong, itu penyebab suara kurang "cess". Sekarang koefisiennya
    // ikut knob Cess: 0.55 (~6kHz, hangat) sampai 0.95 (praktis terbuka).
    float airStateL_ = 0.0f;
    float airStateR_ = 0.0f;

    // Filter sisi input tangki: low-cut (buang dengung bass biar wet gak
    // butek, desis jadi menonjol) + high-pass buat pre-emphasis nada tinggi.
    float lowCutState_ = 0.0f;
    float emphState_ = 0.0f;
    // Kompensasi level tangki: makin panjang feedback comb, makin besar
    // penumpukan energi - input diturunin biar tail panjang gak jadi
    // kenceng/meledak, cuma jadi lebih lama.
    std::atomic<float> tankGain_{0.6f};

    void updateDamping();

    std::atomic<float> cess_{0.6f};
    std::atomic<float> wet_{0.3f};
    std::atomic<float> dry_{1.0f};
    std::atomic<float> width_{1.0f};
    std::atomic<float> roomSize_{0.5f};
    std::atomic<float> damp_{0.5f};
    std::atomic<bool> enabled_{true};
};

// Efek Echo (delay repeat yang jelas kedengeran, beda sama reverb yang
// dengungnya nyampur jadi "kabut"). Prinsipnya SAMA kayak FreeverbStyle di
// atas: buffer delay-nya HANYA dipakai buat MEMBENTUK sinyal echo itu
// sendiri (tap yang muncul belakangan), bukan buat nunda jalur mic->speaker
// utama. Sinyal asli (dry) tetap ditambahkan tanpa nunggu apa pun - jadi
// nyanyi/ngomong tetap kedengeran instan, cuma "gema"-nya aja yang emang
// sengaja telat (itu memang sifat echo, kalau nggak telat ya bukan echo).
// Buffer delay dialokasi sekali di prepare() sesuai delay MAKSIMUM yang
// mungkin dipilih user, BUKAN di audio callback - jadi gak ada alokasi
// memori di real-time thread walau user geser2 knob Delay Time.
class EchoEffect {
public:
    void prepare(int sampleRate);

    // delayMs: jarak waktu tiap repeat-nya kedengeran (mis. 300 = tiap
    // ulangan muncul 300ms setelah yang sebelumnya). Diklamp ke
    // [kMinDelayMs, kMaxDelayMs] dan buffer sudah dialokasi cukup besar
    // buat nampung kMaxDelayMs di prepare(), jadi aman diubah-ubah kapan
    // saja tanpa realokasi.
    void setDelayMs(float ms);
    // feedback: seberapa banyak repeat sebelumnya "diulang lagi" - makin
    // gede makin banyak/panjang echo-nya terdengar. Diklamp ke maks 0.75
    // biar gak jadi feedback loop yang membesar terus (howl/self-oscillate).
    void setFeedback(float fb);
    // mix: level echo yang dicampur ke output (0 = gak kedengeran sama
    // sekali, 1 = paling kenceng). Sinyal dry/asli TIDAK dipengaruhi mix
    // ini - mix cuma ngatur besarnya tap echo yang ditambahkan.
    void setMix(float m);
    // Bypass total: repeat berhenti diproses & buffer di-clear pas
    // dinyalain lagi, biar gak ada sisa echo lama yang "meledak" pas di-on-in.
    void setEnabled(bool e) { enabled_.store(e); }

    // input = sample mic mono frame ini. outL/outR = KONTRIBUSI ECHO SAJA
    // (bukan dry, bukan reverb) - caller yang nambahin ini ke sinyal
    // dry+reverb yang sudah ada, di frame yang sama, tanpa nunda apa pun
    // di jalur utama.
    void process(float input, float &outL, float &outR);

private:
    static constexpr float kMinDelayMs = 50.0f;
    static constexpr float kMaxDelayMs = 600.0f;

    std::vector<float> bufferL_;
    std::vector<float> bufferR_;
    int writeIndex_ = 0;
    int sampleRate_ = 48000;

    std::atomic<float> delayMs_{300.0f};
    std::atomic<int> delaySamples_{0};
    std::atomic<float> feedback_{0.35f};
    std::atomic<float> mix_{0.3f};
    std::atomic<bool> enabled_{false};
};

// EQ 3-band ala channel strip mixer Yamaha (seri MG: EQ LOW 100Hz shelving,
// MID 2.5kHz peaking, HIGH 10kHz shelving, semua +-15dB - dicek dari
// spesifikasi resmi Yamaha). Diimplementasi pakai biquad IIR standar (RBJ
// Audio EQ Cookbook), BUKAN FIR/convolution - artinya TIDAK ADA buffer/delay
// line sama sekali (beda total sama FreeverbStyle/EchoEffect di atas yang
// memang butuh delay line buat bentuk reverb/echo). Tiap sample diproses
// LANGSUNG dari sample itu + 2 sample SEBELUMNYA (state internal filter),
// jadi nol sample tambahan menunggu - filter murni "meruncingkan" bentuk
// gelombang saat itu juga, gak menunda apa pun ke speaker.
class ThreeBandEQ {
public:
    void prepare(int sampleRate);

    // Gain dalam dB, target di-clamp ke +-15dB (identik rentang EQ Yamaha
    // MG). Koefisien biquad dihitung ULANG di sini (bukan di process()) -
    // trig/pow itu berat, jadi sengaja HANYA dihitung pas user geser knob,
    // bukan tiap sample di audio thread.
    void setLowGainDb(float db);
    void setMidGainDb(float db);
    void setHighGainDb(float db);
    void setEnabled(bool e) { enabled_.store(e); }

    // input = 1 sample mono, return = hasil setelah lewat 3 band (berurutan
    // Low -> Mid -> High, sama kayak signal flow di channel strip mixer
    // beneran). Dipanggil SEBELUM sinyal masuk ke reverb/echo - EQ di mixer
    // asli memang di posisi paling awal channel strip (sebelum aux send ke
    // efek), bukan di output akhir.
    float process(float input);

private:
    struct Biquad {
        // Koefisien atomic (ditulis dari UI thread, dibaca dari audio
        // thread) - sample yang KEBETULAN nyebrang pas koefisien lagi
        // ditulis paling cuma "meleset" super dikit sepersekian dB selama
        // 1 sample, gak kedengeran, jauh lebih murah daripada mutex/lock
        // di real-time audio thread.
        std::atomic<float> b0{1.0f}, b1{0.0f}, b2{0.0f}, a1{0.0f}, a2{0.0f};
        // State (x1,x2,y1,y2) HANYA disentuh dari audio thread, jadi aman
        // sebagai float biasa (bukan atomic).
        float x1 = 0.0f, x2 = 0.0f, y1 = 0.0f, y2 = 0.0f;
        float process(float in) {
            const float B0 = b0.load(), B1 = b1.load(), B2 = b2.load();
            const float A1 = a1.load(), A2 = a2.load();
            float out = B0 * in + B1 * x1 + B2 * x2 - A1 * y1 - A2 * y2;
            x2 = x1; x1 = in;
            y2 = y1; y1 = out;
            return out;
        }
    };

    void computeLowShelf(float dbGain);
    void computeHighShelf(float dbGain);
    void computePeaking(float dbGain);

    Biquad low_;
    Biquad mid_;
    Biquad high_;
    int sampleRate_ = 44100;
    std::atomic<bool> enabled_{true};

    // Frekuensi & Q persis spesifikasi Yamaha MG (LOW/MID/HIGH), MID Q
    // dipilih 0.9 - Yamaha gak publish angka Q persisnya, 0.9 itu lebar
    // pita musikal umum buat mid peaking EQ (gak terlalu sempit/notch-y,
    // gak terlalu lebar/pengaruh ke band tetangga).
    static constexpr float kLowFreq = 100.0f;
    static constexpr float kMidFreq = 2500.0f;
    static constexpr float kMidQ = 0.9f;
    static constexpr float kHighFreq = 10000.0f;
    static constexpr float kMaxGainDb = 15.0f;
};

// High-pass 90Hz (biquad, 2nd order) buat buang dengung listrik/"grounding"
// (50/60Hz + bass rumble) dan DC dari mic. IIR = nol delay tambahan.
class HumFilter {
public:
    void prepare(int sampleRate);
    float process(float in) {
        float out = b0_ * in + b1_ * x1_ + b2_ * x2_ - a1_ * y1_ - a2_ * y2_;
        x2_ = x1_; x1_ = in; y2_ = y1_; y1_ = out;
        return out;
    }
private:
    float b0_ = 1, b1_ = 0, b2_ = 0, a1_ = 0, a2_ = 0;
    float x1_ = 0, x2_ = 0, y1_ = 0, y2_ = 0;
};

// Noise gate/expander buat desis & noise lantai mic. Dipasang di sinyal
// MENTAH mic (sebelum Mic Volume), jadi ambangnya tetap walau volume diubah
// dan noise gak ikut dikuatkan. Tanpa lookahead = nol latency tambahan.
// Ada hysteresis + hold biar ujung kata/napas gak terpotong-potong; saat
// "tutup" sinyal diredam ~26dB (bukan dimute total) supaya terdengar alami.
class NoiseGate {
public:
    void prepare(int sampleRate);
    // v 0..1: 0 = gate mati, 1 = ambang paling tinggi (-24 dBFS).
    // Selain itu ambang BUKA = -59 + 35*v dBFS (tutup 6dB di bawahnya).
    void setAmount(float v);
    float process(float in);
private:
    std::atomic<float> openThr_{0.0f}; // linear; 0 = off
    float env_ = 0.0f;
    float gain_ = 1.0f;
    bool open_ = true;
    int holdCounter_ = 0;
    int holdSamples_ = 4800;
    float envRelease_ = 0.999f;
    float envAttack_ = 0.01f;
    float gainUp_ = 0.05f;
    float gainDown_ = 0.0005f;
    int sampleRate_ = 48000;
};

// Jalur karaoke: mic (INPUT stream) -> EQ -> reverb -> speaker (OUTPUT
// stream). Didorong dari callback stream OUTPUT (bukan input) - di setiap
// onAudioReady kita LANGSUNG "narik" (read, non-blocking) sample yang
// barusan ditangkep mic dari input stream, proses, tulis ke buffer output
// di frame yang sama. Ini pola full-duplex standar Oboe yang latensinya
// paling kecil, karena gak ada antrian/ring-buffer perantara antara mic dan
// speaker - satu callback = satu perjalanan mic->speaker.
class KaraokeEngine : public oboe::AudioStreamCallback {
public:
    KaraokeEngine();

    bool start();
    void stop();
    bool isRunning() const { return running_.load(); }

    void setReverbParams(float wet, float dry, float width, float damp, float roomSize);
    void setPreDelayMs(float ms);
    // Cess 0..1 - panjang/terangnya desis S di ekor reverb (lihat FreeverbStyle).
    void setCess(float cess);
    // Noise gate 0..1 (0 = mati) - lihat NoiseGate.
    void setGate(float amount);

    // Preset "Nyes": kombinasi plate/hall medium + pre-delay dikit + slapback
    // echo pendek yang jadi formula umum karaoke Indonesia (vokal tetap
    // jelas di depan, tapi ekor reverbnya lembut & echo-nya kerasa "nempel").
    // Nilai di sini masih dalam satuan ASLI (bukan 0..1 knob) - sisi
    // Kotlin/UI yang nampilin balik ke posisi knob 0..1 terdekat lewat
    // fungsi terpisah, biar slider di UI tetap sinkron abis preset dipakai.
    void applyNyesPreset();

    // delayMs/feedback/mix -> lihat komentar di EchoEffect. Aman dipanggil
    // kapan aja (termasuk pas lagi jalan), semua parameternya atomic.
    void setEchoParams(float delayMs, float feedback, float mix);

    // On/off efek echo doang (mic->speaker & reverb tetep jalan seperti
    // biasa walau ini off - default-nya memang OFF, jadi kalau user gak
    // pernah sentuh knob Echo, perilaku app persis seperti sebelum fitur
    // ini ada, TANPA delay tambahan apa pun).
    void setEchoEnabled(bool enabled);

    // Gain input mic (0..3, 1.0 = suara asli tanpa dinaik/turunin). Dikaliin
    // ke sample mic SEBELUM masuk ke reverb & dry path - jadi naik/turunin
    // "volume mic" itu sendiri, bukan cuma level di output kayak knob Dry.
    // Cuma perkalian 1 angka per sample, TIDAK ada buffer/delay line baru,
    // jadi TIDAK nambah latency sama sekali ke pipeline mic->speaker.
    void setMicGain(float gain);

    // On/off buat efek reverb-nya doang (mic->speaker tetep jalan walau
    // di-off-in, cuma dengungnya yang ilang). Berguna kalau user mau
    // bandingin suara polos vs yang di-reverb tanpa harus geser2 knob Wet.
    void setReverbEnabled(bool enabled);

    // EQ 3-band ala channel strip Yamaha (Low 100Hz shelving, Mid 2.5kHz
    // peaking, High 10kHz shelving), tiap gain dalam dB (+-15). Diproses
    // PALING AWAL sebelum reverb/echo (lihat komentar ThreeBandEQ di atas),
    // sama seperti posisi EQ di channel strip mixer beneran.
    void setEQParams(float lowDb, float midDb, float highDb);
    void setEQEnabled(bool enabled);

    // Level buat visualizer UI (mean-square, bukan amplitudo; akar-nya dihitung
    // di Kotlin). micMs = sinyal mic setelah gate & Mic Vol; outMs = total yang
    // dikirim engine ke speaker (mic+reverb+echo). Cuma baca atomic -> aman.
    float getMicMeanSquare() const { return micMs_.load(std::memory_order_relaxed); }
    float getOutputMeanSquare() const { return outMs_.load(std::memory_order_relaxed); }

    oboe::DataCallbackResult onAudioReady(oboe::AudioStream *stream, void *audioData, int32_t numFrames) override;
    void onErrorBeforeClose(oboe::AudioStream *stream, oboe::Result error) override;
    void onErrorAfterClose(oboe::AudioStream *stream, oboe::Result error) override;

private:
    bool openStreams();
    void closeStreams();
    void reopenAsync();

    std::shared_ptr<oboe::AudioStream> outputStream_;
    std::shared_ptr<oboe::AudioStream> inputStream_;
    HumFilter hum_;
    NoiseGate gate_;
    ThreeBandEQ eq_;
    FreeverbStyle reverb_;
    EchoEffect echo_;
    std::vector<float> inputScratch_; // buffer sementara buat hasil read() mic per callback
    std::atomic<float> micGain_{1.0f};
    std::atomic<float> micMs_{0.0f};
    std::atomic<float> outMs_{0.0f};
    std::atomic<bool> running_{false};
    std::atomic<bool> reopening_{false};
    std::mutex streamMutex_;
    int sampleRate_ = 48000;
};
