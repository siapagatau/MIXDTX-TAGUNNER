#include <jni.h>
#include <memory>
#include "KaraokeEngine.h"
#include "SysReverb.h"

static std::unique_ptr<KaraokeEngine> g_engine;
// Reverb/delay untuk audio sistem: satu objek statis (tidak pernah dihapus), jadi setter dari UI aman
// kapan pun; prepare() hanya dipanggil SEBELUM thread audio service mulai.
static SysReverb g_sys;

extern "C" JNIEXPORT jboolean JNICALL
Java_com_kendang_realpads_AudioEngine_nativeStart(JNIEnv *, jobject) {
    if (!g_engine) g_engine = std::make_unique<KaraokeEngine>();
    return g_engine->start() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_kendang_realpads_AudioEngine_nativeStop(JNIEnv *, jobject) {
    if (g_engine) g_engine->stop();
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_kendang_realpads_AudioEngine_nativeIsRunning(JNIEnv *, jobject) {
    return (g_engine && g_engine->isRunning()) ? JNI_TRUE : JNI_FALSE;
}

// wet/dry/width/damp/roomSize semuanya 0..1, dipanggil tiap kali user geser
// knob di UI (aman dipanggil kapan aja, termasuk pas lagi jalan, karena semua
// parameter reverb disimpen sebagai std::atomic<float>).
extern "C" JNIEXPORT void JNICALL
Java_com_kendang_realpads_AudioEngine_nativeSetReverbParams(
        JNIEnv *, jobject, jfloat wet, jfloat dry, jfloat width, jfloat damp, jfloat roomSize) {
    if (g_engine) g_engine->setReverbParams(wet, dry, width, damp, roomSize);
}

// gain 0..3, dipanggil tiap kali user geser knob Mic Vol. Aman dipanggil
// kapan aja (termasuk pas lagi jalan) karena micGain_ disimpen sebagai
// std::atomic<float> di KaraokeEngine.
extern "C" JNIEXPORT void JNICALL
Java_com_kendang_realpads_AudioEngine_nativeSetMicGain(JNIEnv *, jobject, jfloat gain) {
    if (g_engine) g_engine->setMicGain(gain);
}

extern "C" JNIEXPORT void JNICALL
Java_com_kendang_realpads_AudioEngine_nativeSetReverbEnabled(JNIEnv *, jobject, jboolean enabled) {
    if (g_engine) g_engine->setReverbEnabled(enabled == JNI_TRUE);
}

// preDelayMs 0..80, dipanggil tiap kali user geser knob Pre-Delay (kalau
// UI nambahin knob-nya) atau via preset.
extern "C" JNIEXPORT void JNICALL
Java_com_kendang_realpads_AudioEngine_nativeSetPreDelayMs(JNIEnv *, jobject, jfloat ms) {
    if (g_engine) g_engine->setPreDelayMs(ms);
}

// cess 0..1: panjang/terangnya desis S di ekor reverb (karakter Alesis).
extern "C" JNIEXPORT void JNICALL
Java_com_kendang_realpads_AudioEngine_nativeSetCess(JNIEnv *, jobject, jfloat cess) {
    if (g_engine) g_engine->setCess(cess);
}

// gate 0..1: 0 = noise gate mati, makin besar makin tinggi ambangnya.
extern "C" JNIEXPORT void JNICALL
Java_com_kendang_realpads_AudioEngine_nativeSetGate(JNIEnv *, jobject, jfloat amount) {
    if (g_engine) g_engine->setGate(amount);
}

// lowDb/midDb/highDb: -15..+15, persis rentang EQ channel strip Yamaha MG
// (Low 100Hz shelving, Mid 2.5kHz peaking, High 10kHz shelving). Aman
// dipanggil kapan aja termasuk pas lagi jalan.
extern "C" JNIEXPORT void JNICALL
Java_com_kendang_realpads_AudioEngine_nativeSetEQParams(
        JNIEnv *, jobject, jfloat lowDb, jfloat midDb, jfloat highDb) {
    if (g_engine) g_engine->setEQParams(lowDb, midDb, highDb);
}

extern "C" JNIEXPORT void JNICALL
Java_com_kendang_realpads_AudioEngine_nativeSetEQEnabled(JNIEnv *, jobject, jboolean enabled) {
    if (g_engine) g_engine->setEQEnabled(enabled == JNI_TRUE);
}

// Terapkan preset "Nyes" langsung di native (satu panggilan, atomic per
// parameter - aman dipanggil kapan aja termasuk pas lagi jalan). Sisi
// Kotlin TETAP harus update state knob lokal (lihat MainActivity) biar
// slider di layar ikut geser ke posisi preset, bukan cuma suaranya doang
// yang berubah.
extern "C" JNIEXPORT void JNICALL
Java_com_kendang_realpads_AudioEngine_nativeApplyNyesPreset(JNIEnv *, jobject) {
    if (g_engine) g_engine->applyNyesPreset();
}

// delayMs 50..600, feedback 0..0.75, mix 0..1. Aman dipanggil kapan aja
// (termasuk pas lagi jalan) karena semua parameter echo disimpen sebagai
// std::atomic di native.
extern "C" JNIEXPORT void JNICALL
Java_com_kendang_realpads_AudioEngine_nativeSetEchoParams(
        JNIEnv *, jobject, jfloat delayMs, jfloat feedback, jfloat mix) {
    if (g_engine) g_engine->setEchoParams(delayMs, feedback, mix);
}

extern "C" JNIEXPORT void JNICALL
Java_com_kendang_realpads_AudioEngine_nativeSetEchoEnabled(JNIEnv *, jobject, jboolean enabled) {
    if (g_engine) g_engine->setEchoEnabled(enabled == JNI_TRUE);
}

// Level mic & total output engine (mean-square) buat visualizer. 0 kalau mati.
extern "C" JNIEXPORT jfloat JNICALL
Java_com_kendang_realpads_AudioEngine_nativeGetMicMeanSquare(JNIEnv *, jobject) {
    return (g_engine && g_engine->isRunning()) ? g_engine->getMicMeanSquare() : 0.0f;
}

extern "C" JNIEXPORT jfloat JNICALL
Java_com_kendang_realpads_AudioEngine_nativeGetOutputMeanSquare(JNIEnv *, jobject) {
    return (g_engine && g_engine->isRunning()) ? g_engine->getOutputMeanSquare() : 0.0f;
}

extern "C" JNIEXPORT void JNICALL
Java_com_kendang_realpads_AudioEngine_nativeShutdown(JNIEnv *, jobject) {
    if (g_engine) {
        g_engine->stop();
        g_engine.reset();
    }
}

// ---------------------------------------------------------------------------
// SysReverbEngine (Kotlin object) - reverb + delay per band untuk audio sistem.
// ---------------------------------------------------------------------------
extern "C" JNIEXPORT void JNICALL
Java_com_kendang_realpads_SysReverbEngine_nativePrepare(JNIEnv *, jobject, jint sampleRate) {
    g_sys.prepare((int) sampleRate);
}

// input/output: stereo interleaved int16, panjang >= frames*2. output = efek saja.
extern "C" JNIEXPORT void JNICALL
Java_com_kendang_realpads_SysReverbEngine_nativeProcess(
        JNIEnv *env, jobject, jshortArray input, jshortArray output, jint frames) {
    const jsize inLen = env->GetArrayLength(input);
    const jsize outLen = env->GetArrayLength(output);
    if (frames <= 0 || inLen < frames * 2 || outLen < frames * 2) return;
    jshort *in = env->GetShortArrayElements(input, nullptr);
    jshort *out = env->GetShortArrayElements(output, nullptr);
    if (in && out) g_sys.process(reinterpret_cast<const int16_t *>(in), reinterpret_cast<int16_t *>(out), (int) frames);
    if (in) env->ReleaseShortArrayElements(input, in, JNI_ABORT);
    if (out) env->ReleaseShortArrayElements(output, out, 0);
}

extern "C" JNIEXPORT void JNICALL
Java_com_kendang_realpads_SysReverbEngine_nativeSetReverb(
        JNIEnv *, jobject, jfloat wet, jfloat width, jfloat damp, jfloat room, jfloat cess, jfloat preDelayMs) {
    g_sys.setReverbParams(wet, width, damp, room, cess, preDelayMs);
}

extern "C" JNIEXPORT void JNICALL
Java_com_kendang_realpads_SysReverbEngine_nativeSetEcho(JNIEnv *, jobject, jfloat delayMs, jfloat feedback, jfloat mix) {
    g_sys.setEchoParams(delayMs, feedback, mix);
}

extern "C" JNIEXPORT void JNICALL
Java_com_kendang_realpads_SysReverbEngine_nativeSetSwitches(JNIEnv *, jobject, jboolean reverbOn, jboolean echoOn) {
    g_sys.setSwitches(reverbOn == JNI_TRUE, echoOn == JNI_TRUE);
}

extern "C" JNIEXPORT void JNICALL
Java_com_kendang_realpads_SysReverbEngine_nativeSetBandSend(JNIEnv *, jobject, jint band, jfloat reverbSend, jfloat echoSend) {
    g_sys.setBandSend((int) band, reverbSend, echoSend);
}

extern "C" JNIEXPORT void JNICALL
Java_com_kendang_realpads_SysReverbEngine_nativeSetGain(JNIEnv *, jobject, jfloat gain) {
    g_sys.setGain(gain);
}

extern "C" JNIEXPORT jfloat JNICALL
Java_com_kendang_realpads_SysReverbEngine_nativeGetMeanSquare(JNIEnv *, jobject) {
    return g_sys.getMeanSquare();
}
