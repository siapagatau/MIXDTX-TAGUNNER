# MIXJOS (mix.jos.android.lwbdgt)

APK **DTX** asli yang pad-nya diganti kontrol **mixer** (mic → reverb, echo, EQ, gate → speaker, EQ audio global, panel floating).
Tampilan, palet, panel, strip tombol, tema, background, visual sentuhan, pemilih skin, dan Skin Engine = DTX; yang diganti hanya isinya.

## Susunan layar
Bila skin aktif di APK, panel FLOATING ikut menampilkan skin yang sama (real time, proporsi asli, tinggi jendela menyesuaikan sekali per skin). Tanpa skin, layar utama dan panel FLOATING memakai **satu panel yang sama** (`MixerGrid` di `MixerPanel.kt`): kartu, urutan, warna, huruf, saklar, dan meter identik.
Yang menyesuaikan hanya susunannya terhadap lebar ruang, dari data `MixGroups` yang sama. Tampilan datar (tanpa garis tepi/bayangan).
Dua BAGIAN dengan pita judul berwarna TEMA (hanya judul, tanpa keterangan):
1. **MIKROFON**: kartu MIC (2 knob), REVERB (6), ECHO (3), EQ MIC (3), LEVEL (meter mic). Tiap kartu punya saklar ON/OFF sendiri (ketuk seluruh baris judul kartu).
2. **EQUALIZER AUDIO** (musik / audio HP, bukan mic): GAIN, SUB, LOW, MID, HIGH + meter OUT. Saklar ON/OFF di pita judul (ketuk seluruh pita).
- **LEBAR** (layar utama / floating dilebarkan ≥ 640dp): dua bagian berdampingan, tanpa gulir. MIKROFON 8 kolom `[MIC 2][REVERB 6]` / `[ECHO 3][EQ MIC 3][LEVEL 2]`; EQ AUDIO 3 kolom `[GAIN SUB LOW]` / `[MID HIGH OUT]`.
- **TEGAK** (floating sempit): bagian ditumpuk, 3 knob per baris, digulir: `[MIC 2][LEVEL 1]` / `[REVERB 3+3]` / `[ECHO 3]` / `[EQ MIC 3]`, lalu EQ AUDIO sama dengan susunan lebar.
Saklar/tombol hanya berubah saat DIKETUK (jari diangkat di tempat yang sama); menggeser/scroll tidak mengubahnya. Kelompok yang OFF tampil redup. Strip bawah (layar utama): **FLOATING · TEMA · SKIN**; tab tipis kanan = sembunyikan strip. Pegangan resize floating ada di baris bawah sendiri (tidak menutupi knob).
Knob: DIPUTAR dengan jari mengelilingi pusat knob (searah jarum jam = naik), halus, tidak loncat; ketuk dua kali = nilai awal. Knob EQ terisi dari tengah (0 dB).
Teks tombol (strip bawah, pemilih skin) putih netral, tidak ikut tema. Warna: INDIKATOR (busur knob, meter, LED saklar, FLOATING aktif) = hijau tetap (`IndicatorGreen`). Warna TEMA = pita judul bagian, judul kartu, teks tombol strip.

## Equalizer 31 band (v1.7.0)
Mode yang menyatu di BAWAH mixer (gulir ke bawah): tombol **EQUALIZER** (strip bawah layar utama) / **EQ** (baris bawah floating). L (atas) dan R (bawah) masing-masing 31 fader (20 Hz-20 kHz, ±12 dB) + knob GAIN; ketuk dua kali = 0 dB. Bekerja di suara semua app (efek yang sama dengan EQ AUDIO, dijumlahkan). Skin equalizer terpisah (repo `MIXDTX-EQSKIN`, tab EQUALIZER di pemilih skin; lihat `PROMPT_SKIN_EQUALIZER.md`); mode skin: EQUALIZER menyambung tanpa celah di bawah skin mixer.

## Kompresor (v1.8.0)
Mode yang menyatu di bawah mixer dan DI ATAS equalizer: tombol **KOMPRESOR** (strip bawah) / **KOMP** (baris bawah floating). Knob Attack, Release, Rasio, Frekuensi, Threshold, Gain + Keseimbangan (fokus L/R) + Volume + Setel ulang, bekerja di suara semua app (efek yang sama dengan EQ). Skin terpisah (repo `MIXDTX-COMPSKIN`, tab KOMPRESOR di pemilih skin; lihat `PROMPT_SKIN_KOMPRESOR.md`).

## FX (v1.9.0, gaya taGunner)
Mode ke-3: tombol **FX** (strip bawah) / **FX** (baris bawah floating). Bagian FX di bawah KOMPRESOR, di atas EQUALIZER: FX RACK (SUBSONIC, SUB BASS, WIDENER, TUBE DRIVE, 8D PAN, LIMITER, MBC 10 BAND), PARAMETRIC (grafik + titik Hz/dB/Q). Efek yang sama dengan EQ/kompresor (session 0). Kode: `TaFx.kt`, `TaFxPanel.kt`, `GlobalEqEngine.kt`.

## Skin mixer
Skin Engine DTX + 3 tambahan: `toggle`, `slider`, lampu `meter`. Katalog function dan aturan lengkap: `PROMPT_SKIN_MIXER.md` (taruh di repo `MIXDTX-SKIN`).
Tidak ada skin bawaan di APK: semua skin diunduh dari repo (cache offline setelah diunduh). Repo skin: `https://github.com/siapagatau/MIXDTX-SKIN` (branch master; diatur di `assets/config/skin-repo.json`).

## Struktur kode (package com.kendang.realpads)
- `MainActivity.kt` — activity + `MixerScreen`, `MixActionStrip`, daftar kelompok `MixGroups`.
- `MixerPanel.kt` — `MixerGrid`: SATU-SATUNYA panel mixer (susunan lebar & tegak), dipakai layar utama dan floating.
- `MixerTiles.kt` — `GroupCard`, `MixSection`, `KnobTile`, `Knob`, `MeterTile` (mendatar/tegak otomatis), `VerticalSlider`.
- `DtxPalette.kt`, `ThemeState.kt`, `BackgroundState.kt`, `TouchFx.kt`, `ColorPickerDialog.kt` — dari DTX.
- `FloatingBody.kt`, `FloatingWindowService.kt`, `GlobalEq*`, `SettingsStore/Bus`, `MusicMeter`, `AudioEngine.kt` + `cpp/` (Oboe `KaraokeEngine`) — mesin mixer.
- `skin/` — Skin Engine DTX + `MixerBridge` (satu-satunya penghubung skin/UI → engine; nilai berkunci nama function).

Menambah kontrol mixer baru: tambah function di `FunctionRegistry` + `MixerBridge.values/pushValue`, lalu daftarkan di `MixGroups`. Bila kontrol baru perlu tempat khusus, atur di `MixerWide`/`MixerNarrow` (`MixerPanel.kt`); layar & floating ikut.

## Build
GitHub Actions → **Build Debug APK** (JDK 17, SDK 36, NDK 28.2, CMake 3.22.1, Gradle 9.3.1). Artefak `apk-mixjos-debug`; bila gagal unduh `build-reports`.

## Status verifikasi (jujur)
Kode BELUM dikompilasi dan BELUM dijalankan di perangkat (sandbox tanpa Android SDK/jaringan ke Google Maven); hanya diperiksa statis
(impor, simbol, kurung seimbang). Kirim log Actions bila ada error kompilasi.

## Zoom floating (skin) - v1.3.0
Saat skin aktif, panel floating bisa di-zoom (1x-4x): cubit dua jari, geser dua jari untuk pindah, atau tombol **- / 1x / +** di tengah baris bawah. Zoom hanya memperbesar gambar (tanpa render ulang), jadi instan tapi agak buram di zoom besar. Satu jari tetap mengoperasikan kontrol skin.
