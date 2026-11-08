# PROMPT_SKIN_KOMPRESOR.md — Aturan Skin KOMPRESOR (gaya Poweramp)

Format skin (skin.svg + skin.json + preview.svg, larangan SVG, hit area, toggle/knob/slider/tombol/lampu meter) **SAMA PERSIS** dengan
`PROMPT_SKIN_MIXER.md` dan `PROMPT_SKIN_EQUALIZER.md`. Hanya beda: repo sendiri (**`MIXDTX-COMPSKIN`**, diatur di `assets/config/comp-skin-repo.json`),
cache sendiri (`comp-skin-cache`), tab sendiri di pemilih skin (KOMPRESOR), dan function yang dipakai (`comp.*`).

## Penempatan di layar
Bagian KOMPRESOR muncul **di bawah mixer dan DI ATAS equalizer** (urutan: mixer -> kompresor -> equalizer), menyambung tanpa celah, selebar
layar/jendela, digulir ke bawah. Tinggi = proporsi asli skin (`layout.padZone`, bila tidak ada seluruh viewBox) pada lebar yang tersedia.
Disarankan viewBox 1280x420. Susunan gaya Poweramp: baris 1 Attack · Release · Rasio, baris 2 Frekuensi · Threshold · Gain, lalu Keseimbangan,
Volume, tombol "Setel ulang" dan saklar "Kompresor".

## Function (hanya ini)
| function | tipe | keterangan (nilai 0..1 -> nilai nyata) |
|---|---|---|
| `comp.attack` | rotary / slider | 0,1 .. 200 mdtk (skala log). Awal: 10 mdtk |
| `comp.release` | rotary / slider | 1 .. 2000 mdtk (skala log). Awal: 100 mdtk |
| `comp.ratio` | rotary / slider | 1:1 .. 20:1 (linear). Awal: 2,0 |
| `comp.freq` | rotary / slider | 20 .. 19000 Hz (skala log). Batas ATAS band yang dikompresi; di atasnya lewat tanpa kompresi. Awal: penuh (seluruh frekuensi) |
| `comp.threshold` | rotary / slider | -80 .. 0 dB (linear). Awal: -20 dB |
| `comp.gain` | rotary / slider | 0 .. 40 dB (makeup gain band terkompresi). Awal: 0 dB |
| `comp.balance` | rotary / slider | Keseimbangan: 0 = penuh KIRI, 0.5 = tengah, 1 = penuh KANAN. Sisi yang berlawanan diredam (fokus ke L/R). Awal: 0.5 |
| `comp.volume` | rotary / slider | 0 .. 100 % (hanya meredam). Awal: 100 % |
| `comp.toggle` | toggle | efek kompresor ON/OFF |
| `comp.mode` | toggle | mode kompresor (tampilkan/sembunyikan bagian ini) — opsional |
| `comp.reset` | button | semua knob kembali ke nilai awal ("Setel ulang") |
| `meter.output` | indicator | lampu level keluaran (opsional) |
Function mixer/equalizer lain juga boleh dipakai bila perlu. Satu function = satu kontrol per skin.
Catatan: skin belum bisa menampilkan teks nilai ("2,5mdtk"); hanya posisi knob/slider. Teks nilai hanya ada di tampilan klasik.

## Contoh
Belum ada skin contoh di repo ini. Salin pola skin equalizer (`MIXDTX-EQSKIN/skins/...`) lalu ganti function-nya; hitung sha256+size di `index.json`
repo `MIXDTX-COMPSKIN` (`https://raw.githubusercontent.com/siapagatau/MIXDTX-COMPSKIN/master/index.json`).
