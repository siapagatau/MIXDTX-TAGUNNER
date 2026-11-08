#include "KaraokeEngine.h"
#include <android/log.h>
#include <thread>
#include <cmath>
#include <algorithm>

#define LOG_TAG "KaraokeEngine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// ---------- HumFilter ----------

void HumFilter::prepare(int sampleRate) {
    const float fc = 90.0f, q = 0.7071f;
    const float w0 = 2.0f * (float) M_PI * fc / (float) sampleRate;
    const float cosW = std::cos(w0), alpha = std::sin(w0) / (2.0f * q);
    const float a0 = 1.0f + alpha;
    b0_ = ((1.0f + cosW) * 0.5f) / a0;
    b1_ = -(1.0f + cosW) / a0;
    b2_ = b0_;
    a1_ = (-2.0f * cosW) / a0;
    a2_ = (1.0f - alpha) / a0;
    x1_ = x2_ = y1_ = y2_ = 0.0f;
}

// ---------- NoiseGate ----------

void NoiseGate::prepare(int sampleRate) {
    sampleRate_ = sampleRate;
    holdSamples_ = (int) (0.15f * sampleRate);                      // tahan 150ms
    envRelease_ = std::exp(-1.0f / (0.08f * sampleRate));           // env turun ~80ms
    envAttack_ = 1.0f - std::exp(-1.0f / (0.004f * sampleRate));    // env naik ~4ms (rata-rata level, bukan puncak)
    gainUp_ = 1.0f - std::exp(-1.0f / (0.0005f * sampleRate));      // buka ~0.5ms
    gainDown_ = 1.0f - std::exp(-1.0f / (0.12f * sampleRate));      // tutup ~120ms
    env_ = 0.0f; gain_ = 1.0f; open_ = true; holdCounter_ = 0;
}

void NoiseGate::setAmount(float v) {
    v = std::fmax(0.0f, std::fmin(1.0f, v));
    if (v < 0.02f) { openThr_.store(0.0f); return; }
    openThr_.store(std::pow(10.0f, (-59.0f + 35.0f * v) / 20.0f));
}

float NoiseGate::process(float in) {
    const float thr = openThr_.load();
    if (thr <= 0.0f) { gain_ = 1.0f; open_ = true; return in; }
    const float a = std::fabs(in);
    // Detektor rata-rata level (bukan puncak): puncak hiss acak ~12dB di
    // atas level rata-ratanya, jadi detektor puncak bikin gate susah nutup.
    if (a > env_) env_ += envAttack_ * (a - env_); else env_ *= envRelease_;
    if (env_ > thr) {
        open_ = true;
        holdCounter_ = holdSamples_;
    } else if (open_) {
        // Hysteresis: baru tutup kalau env turun 6dB di bawah ambang buka
        // DAN masa hold habis.
        if (env_ < thr * 0.5f) {
            if (holdCounter_ > 0) --holdCounter_; else open_ = false;
        } else {
            holdCounter_ = holdSamples_;
        }
    }
    const float target = open_ ? 1.0f : 0.05f;
    gain_ += (target - gain_) * (target > gain_ ? gainUp_ : gainDown_);
    return in * gain_;
}

// ---------- ThreeBandEQ ----------

void ThreeBandEQ::prepare(int sampleRate) {
    sampleRate_ = sampleRate;
    // Reset state internal filter (x1/x2/y1/y2) satu-satu - BUKAN
    // `low_ = Biquad()` (itu butuh copy-assignment, tapi std::atomic di
    // dalam Biquad sengaja gak punya copy-assignment, jadi gak kompel).
    // Koefisien (b0/b1/b2/a1/a2) gak perlu direset manual di sini karena
    // langsung ditimpa oleh compute*() tepat di bawah.
    low_.x1 = low_.x2 = low_.y1 = low_.y2 = 0.0f;
    mid_.x1 = mid_.x2 = mid_.y1 = mid_.y2 = 0.0f;
    high_.x1 = high_.x2 = high_.y1 = high_.y2 = 0.0f;
    computeLowShelf(0.0f);
    computePeaking(0.0f);
    computeHighShelf(0.0f);
}

// Rumus shelving/peaking di bawah ini persis "Audio EQ Cookbook" (RBJ) -
// standar industri buat EQ digital, dipakai di hampir semua DAW/plugin EQ.
void ThreeBandEQ::computeLowShelf(float dbGain) {
    dbGain = std::fmax(-kMaxGainDb, std::fmin(kMaxGainDb, dbGain));
    const float A = std::pow(10.0f, dbGain / 40.0f);
    const float w0 = 2.0f * (float) M_PI * kLowFreq / (float) sampleRate_;
    const float cosW0 = std::cos(w0);
    const float sinW0 = std::sin(w0);
    const float sqrtA = std::sqrt(A);
    // S = 1 (shelf slope "standar", paling umum dipakai buat low/high shelf
    // musikal - transisi landai, bukan curam kayak filter brickwall).
    const float alpha = sinW0 * 0.5f * std::sqrt((A + 1.0f / A) * (1.0f / 1.0f - 1.0f) + 2.0f);

    const float b0 = A * ((A + 1) - (A - 1) * cosW0 + 2 * sqrtA * alpha);
    const float b1 = 2 * A * ((A - 1) - (A + 1) * cosW0);
    const float b2 = A * ((A + 1) - (A - 1) * cosW0 - 2 * sqrtA * alpha);
    const float a0 = (A + 1) + (A - 1) * cosW0 + 2 * sqrtA * alpha;
    const float a1 = -2 * ((A - 1) + (A + 1) * cosW0);
    const float a2 = (A + 1) + (A - 1) * cosW0 - 2 * sqrtA * alpha;

    low_.b0.store(b0 / a0); low_.b1.store(b1 / a0); low_.b2.store(b2 / a0);
    low_.a1.store(a1 / a0); low_.a2.store(a2 / a0);
}

void ThreeBandEQ::computeHighShelf(float dbGain) {
    dbGain = std::fmax(-kMaxGainDb, std::fmin(kMaxGainDb, dbGain));
    const float A = std::pow(10.0f, dbGain / 40.0f);
    const float w0 = 2.0f * (float) M_PI * kHighFreq / (float) sampleRate_;
    const float cosW0 = std::cos(w0);
    const float sinW0 = std::sin(w0);
    const float sqrtA = std::sqrt(A);
    const float alpha = sinW0 * 0.5f * std::sqrt((A + 1.0f / A) * (1.0f / 1.0f - 1.0f) + 2.0f);

    const float b0 = A * ((A + 1) + (A - 1) * cosW0 + 2 * sqrtA * alpha);
    const float b1 = -2 * A * ((A - 1) + (A + 1) * cosW0);
    const float b2 = A * ((A + 1) + (A - 1) * cosW0 - 2 * sqrtA * alpha);
    const float a0 = (A + 1) - (A - 1) * cosW0 + 2 * sqrtA * alpha;
    const float a1 = 2 * ((A - 1) - (A + 1) * cosW0);
    const float a2 = (A + 1) - (A - 1) * cosW0 - 2 * sqrtA * alpha;

    high_.b0.store(b0 / a0); high_.b1.store(b1 / a0); high_.b2.store(b2 / a0);
    high_.a1.store(a1 / a0); high_.a2.store(a2 / a0);
}

void ThreeBandEQ::computePeaking(float dbGain) {
    dbGain = std::fmax(-kMaxGainDb, std::fmin(kMaxGainDb, dbGain));
    const float A = std::pow(10.0f, dbGain / 40.0f);
    const float w0 = 2.0f * (float) M_PI * kMidFreq / (float) sampleRate_;
    const float cosW0 = std::cos(w0);
    const float sinW0 = std::sin(w0);
    const float alpha = sinW0 / (2.0f * kMidQ);

    const float b0 = 1 + alpha * A;
    const float b1 = -2 * cosW0;
    const float b2 = 1 - alpha * A;
    const float a0 = 1 + alpha / A;
    const float a1 = -2 * cosW0;
    const float a2 = 1 - alpha / A;

    mid_.b0.store(b0 / a0); mid_.b1.store(b1 / a0); mid_.b2.store(b2 / a0);
    mid_.a1.store(a1 / a0); mid_.a2.store(a2 / a0);
}

void ThreeBandEQ::setLowGainDb(float db) { computeLowShelf(db); }
void ThreeBandEQ::setMidGainDb(float db) { computePeaking(db); }
void ThreeBandEQ::setHighGainDb(float db) { computeHighShelf(db); }

float ThreeBandEQ::process(float input) {
    if (!enabled_.load()) return input;
    float out = low_.process(input);
    out = mid_.process(out);
    out = high_.process(out);
    return out;
}

// ---------- FreeverbStyle ----------

void FreeverbStyle::prepare(int sampleRate) {
    // Tuning dasar Freeverb itu di-desain buat 44100Hz - kita skalain ke
    // sampleRate device sekarang biar karakter reverb-nya (panjang delay
    // line) sama walaupun device jalan di 48000Hz atau rate lain.
    sampleRate_ = sampleRate;
    const float scale = (float) sampleRate / 44100.0f;
    // Comb ke-0 & ke-2 yang dimodulasi (lihat process()) - dipilih yang
    // ukurannya beda jauh biar shimmer-nya gak "senada"/fasa sama terus.
    // 8 comb + 4 allpass = tuning Freeverb penuh: ekor lebih rapat &
    // halus (4 comb dulu kedengeran metalik/kosong, bikin desis "s" gak
    // sempat menyebar jadi "cesss" yang lembut).
    const int combTuningL[kNumCombs] = {1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617};
    const int stereoSpread = (int) std::lround(23 * scale);
    const int allpassTuningL[kNumAllpasses] = {556, 441, 341, 225};

    // Extra headroom di buffer comb yang dimodulasi, buat nampung geseran
    // baca akibat LFO (kLfoDepthSamples) tanpa index nyasar ke luar buffer.
    const int modHeadroom = (int) kLfoDepthSamples + 4;
    for (int i = 0; i < kNumCombs; ++i) {
        int sizeL = (int) std::lround(combTuningL[i] * scale);
        bool modulated = (i < kNumModulatedCombs);
        combsL_[i].setSize(sizeL + (modulated ? modHeadroom : 0));
        combsR_[i].setSize(sizeL + stereoSpread + (modulated ? modHeadroom : 0));
    }
    for (int i = 0; i < kNumAllpasses; ++i) {
        int sizeL = (int) std::lround(allpassTuningL[i] * scale);
        allpassL_[i].setSize(sizeL);
        allpassL_[i].feedback = 0.5f;
        allpassR_[i].setSize(sizeL + stereoSpread);
        allpassR_[i].feedback = 0.5f;
    }

    const int maxPreDelaySamples = (int) std::lround((kMaxPreDelayMs / 1000.0f) * sampleRate) + 4;
    preDelayBuf_.assign(maxPreDelaySamples, 0.0f);
    preDelayWrite_ = 0;
    lfoPhaseL_ = 0.0f;
    lfoPhaseR_ = 1.57f;
    airStateL_ = 0.0f;
    airStateR_ = 0.0f;
    lowCutState_ = 0.0f;
    emphState_ = 0.0f;

    setRoomSize(0.6f);
    setDamp(0.3f);
    setCess(0.6f);
    setWidth(1.0f);
    setPreDelayMs(15.0f);
}

void FreeverbStyle::setPreDelayMs(float ms) {
    ms = std::fmax(0.0f, std::fmin(kMaxPreDelayMs, ms));
    int samples = (int) std::lround((ms / 1000.0f) * sampleRate_);
    if (!preDelayBuf_.empty()) {
        samples = std::min(samples, (int) preDelayBuf_.size() - 1);
    }
    preDelaySamples_.store(std::max(0, samples));
}

void FreeverbStyle::setRoomSize(float v) {
    roomSize_.store(v);
    // Feedback comb 0.72 (ruang kecil) sampai 0.985 (ruang gede, ekor
    // panjang banget) - dinaikin dikit dari rentang klasik Freeverb biar
    // ekor/desis bisa benar-benar panjang di ujung knob.
    float feedback = v * 0.265f + 0.72f;
    for (auto &c: combsL_) c.feedback = feedback;
    for (auto &c: combsR_) c.feedback = feedback;
    // Normalisasi level: energi tangki naik ~1/sqrt(1-fb^2) saat feedback
    // membesar. Dikompensasi di input supaya ekor panjang = lebih LAMA,
    // bukan lebih KENCENG (dikalibrasi supaya level wet setara versi lama).
    const float norm = std::sqrt(std::fmax(0.01f, 1.0f - feedback * feedback));
    tankGain_.store(0.27f * norm);
}

void FreeverbStyle::setDamp(float v) {
    damp_.store(v);
    updateDamping();
}

void FreeverbStyle::setCess(float v) {
    cess_.store(std::fmax(0.0f, std::fmin(1.0f, v)));
    updateDamping();
}

// Redaman nada tinggi di dalam feedback tiap comb. Knob Damp ngatur
// "gelap-terangnya" ruang, TAPI Cess mengurangi redaman itu: di Cess penuh
// redaman tinggal ~25% dari nilai Damp, jadi nada tinggi (desis S) ikut
// berputar di feedback selama ekor reverb - itulah "ceeesss" yang panjang.
void FreeverbStyle::updateDamping() {
    const float cess = cess_.load();
    const float damp1 = damp_.load() * 0.4f * (1.0f - 0.75f * cess);
    const float damp2 = 1.0f - damp1;
    for (auto &c: combsL_) { c.damp1 = damp1; c.damp2 = damp2; }
    for (auto &c: combsR_) { c.damp1 = damp1; c.damp2 = damp2; }
}

void FreeverbStyle::setWidth(float v) {
    width_.store(v);
}

void FreeverbStyle::process(float input, float &outL, float &outR) {
    if (!enabled_.load()) {
        // Bypass: full volume, TIDAK dikalikan knob Dry. Dry cuma relevan
        // sebagai penyeimbang dry/wet WAKTU reverb nyala - begitu reverb
        // di-off-in, gak ada lagi campuran dry/wet, jadi mic harus tetap
        // kedengar penuh walau sebelumnya user udah nurunin Dry buat
        // nonjolin Wet. (Dulu: outL/outR = dry * input, bikin mic ikutan
        // pelan/nyaris hilang kalau Dry kebetulan lagi rendah.)
        // Delay line comb/allpass SENGAJA gak disentuh (gak diproses) -
        // hemat CPU dan pas di-on-in lagi gak ada "ledakan" sisa energi
        // lama yang numpuk selama off.
        outL = input;
        outR = input;
        return;
    }
    // 1) Pre-delay: input masuk buffer dulu, tangki comb/allpass makan
    // sample yang keluar dari buffer ini (yang lebih "lama" sejumlah
    // preDelaySamples_), BUKAN input mentah langsung. Sinyal dry di bagian
    // paling bawah TETAP pakai `input` asli (nol delay) - jadi cuma bagian
    // wet yang mulai belakangan, persis efek pre-delay di reverb unit
    // beneran.
    float tankInput = input;
    if (!preDelayBuf_.empty()) {
        const int size = (int) preDelayBuf_.size();
        const int delaySamples = preDelaySamples_.load();
        int readIndex = preDelayWrite_ - delaySamples;
        if (readIndex < 0) readIndex += size;
        tankInput = preDelayBuf_[readIndex];
        preDelayBuf_[preDelayWrite_] = input;
        if (++preDelayWrite_ >= size) preDelayWrite_ = 0;
    }

    // 2) LFO goyang halus buat comb yang termasuk kNumModulatedCombs -
    // frekuensi & depth-nya sengaja kecil (lihat komentar di header), jadi
    // efeknya "melembutkan" tail, bukan bikin efek chorus yang kedengeran
    // terpisah.
    const float lfoInc = 2.0f * (float) M_PI * kLfoRateHz / (float) sampleRate_;
    lfoPhaseL_ += lfoInc;
    if (lfoPhaseL_ > 2.0f * (float) M_PI) lfoPhaseL_ -= 2.0f * (float) M_PI;
    lfoPhaseR_ += lfoInc;
    if (lfoPhaseR_ > 2.0f * (float) M_PI) lfoPhaseR_ -= 2.0f * (float) M_PI;
    const float modOffsetL = (std::sin(lfoPhaseL_) * 0.5f + 0.5f) * kLfoDepthSamples;
    const float modOffsetR = (std::sin(lfoPhaseR_) * 0.5f + 0.5f) * kLfoDepthSamples;

    // 1b) Pengkondisian input tangki: buang bass (< ~150Hz) biar ekor gak
    // butek, lalu pre-emphasis nada tinggi (> ~4kHz) sebesar Cess - ini
    // yang "mengisi" tangki dengan energi desis S/C/T supaya meluruh
    // panjang di dalam reverb, bukan cuma lewat sekilas di jalur dry.
    const float cess = cess_.load();
    lowCutState_ += 0.02f * (tankInput - lowCutState_);
    float tankIn = tankInput - lowCutState_;
    emphState_ += 0.4f * (tankIn - emphState_);
    const float highBand = tankIn - emphState_;
    tankIn = (tankIn + cess * 1.4f * highBand) * tankGain_.load();
    tankInput = tankIn;

    float combOutL = 0.0f, combOutR = 0.0f;
    for (int i = 0; i < kNumCombs; ++i) {
        if (i < kNumModulatedCombs) {
            combOutL += combsL_[i].processModulated(tankInput, modOffsetL);
            combOutR += combsR_[i].processModulated(tankInput, modOffsetR);
        } else {
            combOutL += combsL_[i].process(tankInput);
            combOutR += combsR_[i].process(tankInput);
        }
    }
    for (auto &a: allpassL_) combOutL = a.process(combOutL);
    for (auto &a: allpassR_) combOutR = a.process(combOutR);

    // 3) Filter "air": one-pole low-pass di ujung. Terbukanya ikut Cess
    // (lihat komentar airStateL_ di header) - dulu konstan & terlalu gelap.
    const float airCoeff = 0.55f + 0.40f * cess;
    airStateL_ += airCoeff * (combOutL - airStateL_);
    airStateR_ += airCoeff * (combOutR - airStateR_);
    combOutL = airStateL_;
    combOutR = airStateR_;

    const float width = width_.load();
    const float wet1 = width * 0.5f + 0.5f;
    const float wet2 = (1.0f - width) * 0.5f;
    const float wetL = combOutL * wet1 + combOutR * wet2;
    const float wetR = combOutR * wet1 + combOutL * wet2;

    const float wet = wet_.load();
    const float dry = dry_.load();
    outL = dry * input + wet * wetL;
    outR = dry * input + wet * wetR;
}

// ---------- EchoEffect ----------

void EchoEffect::prepare(int sampleRate) {
    sampleRate_ = sampleRate;
    // Alokasi buffer cukup buat delay MAKSIMUM yang bisa dipilih user (+
    // sedikit headroom), sekali di sini, sebelum stream jalan. Setelah ini
    // setDelayMs() TINGGAL geser posisi baca di dalam buffer yang sama -
    // gak ada resize/alokasi baru lagi di real-time thread.
    const int maxSamples = (int) std::lround((kMaxDelayMs / 1000.0f) * sampleRate) + 64;
    bufferL_.assign(maxSamples, 0.0f);
    bufferR_.assign(maxSamples, 0.0f);
    writeIndex_ = 0;
    setDelayMs(delayMs_.load());
}

void EchoEffect::setDelayMs(float ms) {
    ms = std::fmax(kMinDelayMs, std::fmin(kMaxDelayMs, ms));
    delayMs_.store(ms);
    int samples = (int) std::lround((ms / 1000.0f) * sampleRate_);
    if (!bufferL_.empty()) {
        samples = std::min(samples, (int) bufferL_.size() - 1);
    }
    delaySamples_.store(std::max(1, samples));
}

void EchoEffect::setFeedback(float fb) {
    // Klamp ke 0..0.75: di atas ini repeat-nya bisa numpuk makin lama makin
    // kenceng (self-oscillate) alih-alih meluruh - bukan efek echo yang
    // enak didengar lagi, cuma noise yang membesar.
    feedback_.store(std::fmax(0.0f, std::fmin(0.75f, fb)));
}

void EchoEffect::setMix(float m) {
    mix_.store(std::fmax(0.0f, std::fmin(1.0f, m)));
}

void EchoEffect::process(float input, float &outL, float &outR) {
    if (!enabled_.load() || bufferL_.empty()) {
        outL = 0.0f;
        outR = 0.0f;
        return;
    }
    const int delaySamples = delaySamples_.load();
    const int size = (int) bufferL_.size();
    // Baca tap yang sudah "menunggu" delaySamples yang lalu - INI bagian
    // yang secara sengaja telat (namanya juga echo), tapi ini gak nunda
    // sample `input` sekarang buat keluar duluan lewat jalur dry di caller.
    int readIndex = writeIndex_ - delaySamples;
    if (readIndex < 0) readIndex += size;

    const float feedback = feedback_.load();
    const float mix = mix_.load();
    const float tapL = bufferL_[readIndex];
    const float tapR = bufferR_[readIndex];

    // Ping-pong sederhana: repeat baru di L dibentuk dari input + feedback
    // tap kanan (dan sebaliknya buat R), jadi keteknya "mantul" kiri-kanan
    // makin lama makin pelan, bukan cuma satu channel yang ngulang sendiri.
    bufferL_[writeIndex_] = input + tapR * feedback;
    bufferR_[writeIndex_] = input + tapL * feedback;

    if (++writeIndex_ >= size) writeIndex_ = 0;

    outL = tapL * mix;
    outR = tapR * mix;
}

// ---------- KaraokeEngine ----------

KaraokeEngine::KaraokeEngine() {
    inputScratch_.resize(4096);
}

bool KaraokeEngine::openStreams() {
    // OUTPUT dulu (speaker/headphone) - stream ini yang jadi "penggerak"
    // (punya callback), latency-nya diminimalkan lewat LowLatency + Shared
    // (lihat alasan kenapa BUKAN Exclusive di komentar setSharingMode di
    // bawah - Exclusive bikin app musik lain gak bisa keputer bareng).
    oboe::AudioStreamBuilder outBuilder;
    outBuilder.setDirection(oboe::Direction::Output)
            ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
            // Shared (BUKAN Exclusive) - Exclusive minta jalur hardware
            // output buat app ini SENDIRIAN (kalau HP support MMap
            // exclusive path). Efeknya: pas mic nyala, app musik/backing
            // track LAIN jadi gak kebagian jalur mixing ke speaker sama
            // sekali (gejala "musik lain gak bisa keputer pas mic hidup").
            // Shared tetap lewat AAudio/OpenSL LowLatency (jalur cepat),
            // cuma sample-nya dicampur system mixer bareng stream app lain
            // sebelum ke hardware - jadi musik app lain tetap bisa bunyi
            // bareng. Latency nambah dikit (masih kecil), tapi ini harga
            // yang wajar buat bisa hidup berdampingan sama app lain.
            ->setSharingMode(oboe::SharingMode::Shared)
            ->setFormat(oboe::AudioFormat::Float)
            ->setChannelCount(2)
            // Usage::Media (BUKAN Alarm, BUKAN VoiceCommunication, dan
            // BUKAN AssistanceAccessibility) - histori kenapa nyampe ke sini:
            //
            // 1. VoiceCommunication ditolak: Android otomatis nyalain
            //    Acoustic Echo Cancellation sistem yang "membuang" ekor
            //    reverb kita sendiri (dianggap echo yang harus dibuang).
            //
            // 2. AssistanceAccessibility ditolak: SECARA NAMA kedengerannya
            //    "stream terpisah" (STREAM_ACCESSIBILITY), tapi ternyata
            //    Android punya tabel internal "volume stream alias" yang
            //    men-SAMA-KAN index STREAM_ACCESSIBILITY dengan STREAM_MUSIC
            //    secara default di hampir semua device.
            //
            // 3. Alarm (SEMPAT dipakai): secara struktural gak numpang index
            //    volume STREAM_MUSIC manapun (bagus), TAPI kategori
            //    Alarm/Notification di Android didesain buat "mengalahkan"
            //    suara lain - jadi SELAMA stream ini aktif (yang buat kita
            //    artinya SELAMA mic nyala, bukan cuma sesaat kayak alarm
            //    beneran), Android terus-menerus nge-duck/membungkam
            //    STREAM_MUSIC app LAIN (musik/backing track keliatan
            //    "playing" tapi senyap total). Ini baked-in di audio policy
            //    sistem buat kategori Alarm/Notification, gak bisa
            //    dimatikan dari sisi app.
            //
            // Jadi Media dipilih lagi meski nempel balik ke STREAM_MUSIC -
            // konsekuensinya: nurunin volume musik sistem (mis. tombol fisik
            // HP) ikut ngecilin loudness mic juga, karena satu bus yang
            // sama. Itu perilaku yang sama seperti app pemutar musik biasa,
            // jadi dibiarkan apa adanya (gak ada kompensasi otomatis).
            ->setUsage(oboe::Usage::Media)
            ->setContentType(oboe::ContentType::Music)
            ->setCallback(this);

    oboe::Result result = outBuilder.openStream(outputStream_);
    if (result != oboe::Result::OK) {
        LOGE("Buka output stream gagal: %s", oboe::convertToText(result));
        return false;
    }
    sampleRate_ = outputStream_->getSampleRate();

    // INPUT (mic clip) - HARUS sample rate & performance mode senada sama
    // output biar gak perlu resample di real-time thread (resample nambah
    // latency & CPU). InputPreset::Unprocessed dipilih karena ini preset yang
    // secara eksplisit MATIKAN semua pemrosesan otomatis OS (echo-cancel, noise
    // suppression, auto-gain) - kita mau sinyal mic yang seapa-adanya mungkin,
    // biar reverb yang kita tambahin sendiri gak "dimakan"/dipotong sama AEC/NS
    // sistem (yang biasa didesain buat panggilan telepon, bukan performa musik).
    oboe::AudioStreamBuilder inBuilder;
    inBuilder.setDirection(oboe::Direction::Input)
            ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
            ->setSharingMode(oboe::SharingMode::Exclusive)
            ->setFormat(oboe::AudioFormat::Float)
            ->setChannelCount(1)
            ->setSampleRate(sampleRate_)
            ->setInputPreset(oboe::InputPreset::Unprocessed);

    result = inBuilder.openStream(inputStream_);
    if (result != oboe::Result::OK) {
        LOGI("Exclusive input gagal (%s), coba Shared...", oboe::convertToText(result));
        inBuilder.setSharingMode(oboe::SharingMode::Shared);
        result = inBuilder.openStream(inputStream_);
        if (result != oboe::Result::OK) {
            LOGE("Buka input stream gagal: %s", oboe::convertToText(result));
            outputStream_->close();
            outputStream_.reset();
            return false;
        }
    }

    hum_.prepare(sampleRate_);
    gate_.prepare(sampleRate_);
    eq_.prepare(sampleRate_);
    reverb_.prepare(sampleRate_);
    echo_.prepare(sampleRate_);

    // Buffer size kecil = latency kecil. Set ke 2x burst size (minimum aman
    // yang direkomendasikan Oboe biar gak gampang xrun/glitch tapi tetep
    // "kerasa" real-time buat karaoke).
    outputStream_->setBufferSizeInFrames(outputStream_->getFramesPerBurst() * 2);

    if (inputStream_->requestStart() != oboe::Result::OK) {
        LOGE("Start input stream gagal");
        return false;
    }
    if (outputStream_->requestStart() != oboe::Result::OK) {
        LOGE("Start output stream gagal");
        return false;
    }
    return true;
}

void KaraokeEngine::closeStreams() {
    if (outputStream_) {
        outputStream_->stop();
        outputStream_->close();
        outputStream_.reset();
    }
    if (inputStream_) {
        inputStream_->stop();
        inputStream_->close();
        inputStream_.reset();
    }
}

bool KaraokeEngine::start() {
    std::lock_guard<std::mutex> lock(streamMutex_);
    if (running_.load()) return true;
    if (!openStreams()) {
        closeStreams();
        return false;
    }
    running_.store(true);
    return true;
}

void KaraokeEngine::stop() {
    std::lock_guard<std::mutex> lock(streamMutex_);
    if (!running_.load()) return;
    running_.store(false);
    closeStreams();
}

void KaraokeEngine::setReverbParams(float wet, float dry, float width, float damp, float roomSize) {
    reverb_.setWet(wet);
    reverb_.setDry(dry);
    reverb_.setWidth(width);
    reverb_.setDamp(damp);
    reverb_.setRoomSize(roomSize);
}

void KaraokeEngine::setPreDelayMs(float ms) {
    reverb_.setPreDelayMs(ms);
}

void KaraokeEngine::setCess(float cess) {
    reverb_.setCess(cess);
}

void KaraokeEngine::setGate(float amount) {
    gate_.setAmount(amount);
}

void KaraokeEngine::applyNyesPreset() {
    // Formula "nyes" ala karaoke: plate/hall medium (roomSize sedang, damp
    // sedang biar gak berisik tapi masih "hidup"), wet cukup terasa tapi
    // dry tetap dominan (vokal jangan sampe tenggelam), width lebar penuh,
    // pre-delay kecil biar konsonan tetep jelas sebelum dengung nyusul.
    // Echo dipasang sebagai slapback pendek (bukan delay panjang berulang)
    // - kombinasi plate reverb + slapback ini yang jadi ciri khas "nyes"-nya
    // karaoke Indonesia (beda sama reverb polos yang cuma "basah" doang).
    reverb_.setRoomSize(0.62f);
    reverb_.setDamp(0.3f);
    reverb_.setCess(0.7f);
    reverb_.setWidth(1.0f);
    reverb_.setWet(0.38f);
    reverb_.setDry(0.85f);
    reverb_.setPreDelayMs(18.0f);
    reverb_.setEnabled(true);

    echo_.setDelayMs(110.0f);
    echo_.setFeedback(0.18f);
    echo_.setMix(0.22f);
    echo_.setEnabled(true);
}

void KaraokeEngine::setMicGain(float gain) {
    micGain_.store(std::fmax(0.0f, std::fmin(3.0f, gain)));
}

void KaraokeEngine::setReverbEnabled(bool enabled) {
    reverb_.setEnabled(enabled);
}

void KaraokeEngine::setEQParams(float lowDb, float midDb, float highDb) {
    eq_.setLowGainDb(lowDb);
    eq_.setMidGainDb(midDb);
    eq_.setHighGainDb(highDb);
}

void KaraokeEngine::setEQEnabled(bool enabled) {
    eq_.setEnabled(enabled);
}

void KaraokeEngine::setEchoParams(float delayMs, float feedback, float mix) {
    echo_.setDelayMs(delayMs);
    echo_.setFeedback(feedback);
    echo_.setMix(mix);
}

void KaraokeEngine::setEchoEnabled(bool enabled) {
    echo_.setEnabled(enabled);
}

// Limiter lembut di output: di bawah 0.85 sinyal lewat apa adanya, di atas
// itu ditekuk halus pakai tanh (bukan dipotong keras) - desis reverb yang
// sekarang lebih terang gak pecah/kasar kalau puncaknya nabrak 0 dBFS.
static inline float softClip(float x) {
    const float t = 0.85f;
    const float ax = std::fabs(x);
    if (ax <= t) return x;
    const float y = t + (1.0f - t) * std::tanh((ax - t) / (1.0f - t));
    return x < 0.0f ? -y : y;
}

oboe::DataCallbackResult
KaraokeEngine::onAudioReady(oboe::AudioStream *stream, void *audioData, int32_t numFrames) {
    auto *out = static_cast<float *>(audioData);

    if ((int) inputScratch_.size() < numFrames) {
        inputScratch_.resize(numFrames);
    }

    int32_t framesRead = 0;
    if (inputStream_) {
        // timeoutNanos = 0 -> non-blocking. Ini KUNCI latency rendahnya: kita
        // gak nunggu mic, cuma ambil apa yang UDAH ada di buffer input mic
        // saat ini juga, di frame output yang sama. Kalau belum ada data baru
        // (framesRead < numFrames), sisanya diisi diam (0) - jauh lebih baik
        // daripada nge-block callback output (yang bisa bikin speaker putus2).
        auto result = inputStream_->read(inputScratch_.data(), numFrames, 0);
        if (result) {
            framesRead = result.value();
        }
    }

    const float gain = micGain_.load();
    double micSumSq = 0.0;
    double outSumSq = 0.0;
    for (int32_t i = 0; i < numFrames; ++i) {
        // Perkalian doang, gak ada buffer/state baru -> nol tambahan delay.
        // Sample lewat gain ini di frame yang SAMA persis kayak sample mic
        // itu dibaca, jadi mic->speaker tetep "satu frame, satu perjalanan".
        // Urutan: buang dengung -> noise gate (di sinyal MENTAH, sebelum
        // gain) -> Mic Volume -> EQ -> reverb/echo. Semua IIR/perkalian,
        // tanpa buffer, jadi tetap nol latency tambahan.
        float raw = (i < framesRead) ? inputScratch_[i] : 0.0f;
        raw = gate_.process(hum_.process(raw));
        float mic = raw * gain;
        micSumSq += (double) mic * mic;
        // EQ diproses PALING AWAL (persis posisi di channel strip mixer
        // beneran: gain trim -> EQ -> aux send ke efek), sebelum reverb &
        // echo. Biquad IIR = nol buffer/delay tambahan, cuma "membentuk
        // ulang" sample yang sama di tempat.
        mic = eq_.process(mic);
        float l, r;
        reverb_.process(mic, l, r);
        // Echo diproses dari sample mic yang SAMA, paralel sama reverb -
        // bukan dirantai setelah reverb. Kalau echo dirantai, tap
        // reverb-nya sendiri jadi keulang lagi (numpuk/berantakan) dan
        // jalur dry ikut lewat 2 tahap buffer. Dengan diproses paralel dari
        // input mentah, dry (l/r dari reverb_, yang sudah termasuk dry
        // asli) tetap nol tambahan delay, echo cuma NUMPANG NAMBAH di
        // frame yang sama.
        float el, er;
        echo_.process(mic, el, er);
        out[i * 2] = softClip(l + el);
        out[i * 2 + 1] = softClip(r + er);
        outSumSq += (double) out[i * 2] * out[i * 2] + (double) out[i * 2 + 1] * out[i * 2 + 1];
    }

    // Level buat visualizer (cuma 2 store atomic per callback, gak ada alokasi).
    if (numFrames > 0) {
        micMs_.store((float) (micSumSq / numFrames), std::memory_order_relaxed);
        outMs_.store((float) (outSumSq / (2.0 * numFrames)), std::memory_order_relaxed);
    }

    return oboe::DataCallbackResult::Continue;
}

void KaraokeEngine::onErrorBeforeClose(oboe::AudioStream *stream, oboe::Result error) {
    LOGE("Stream error sebelum ditutup: %s", oboe::convertToText(error));
}

void KaraokeEngine::onErrorAfterClose(oboe::AudioStream *stream, oboe::Result error) {
    // Kejadian umum: headset/mic clip dicabut-pasang di tengah pemakaian, atau
    // ada app lain yang minta jalur audio exclusive. Kita coba buka ulang di
    // thread terpisah (BUKAN di audio thread yang lagi manggil callback ini)
    // biar app pulih sendiri tanpa user harus tutup-buka app secara manual.
    LOGI("Stream error setelah ditutup (%s), coba reconnect...", oboe::convertToText(error));
    if (reopening_.exchange(true)) return;
    std::thread([this]() {
        {
            std::lock_guard<std::mutex> lock(streamMutex_);
            closeStreams();
            if (running_.load()) {
                openStreams();
            }
        }
        reopening_.store(false);
    }).detach();
}
