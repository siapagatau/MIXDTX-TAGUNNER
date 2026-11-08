# PROMPT_SKIN_EQUALIZER.md — Aturan Skin EQUALIZER 31 BAND

Format skin (skin.svg + skin.json + preview.svg, larangan SVG, hit area, toggle/knob/slider/tombol/lampu meter) **SAMA PERSIS** dengan
`PROMPT_SKIN_MIXER.md`. Hanya beda: repo sendiri (`MIXDTX-EQSKIN`, diatur di `assets/config/eq-skin-repo.json`), cache sendiri, dan function yang dipakai.

## Penempatan di layar
Bagian EQUALIZER **menyambung tepat di bawah** skin mixer (tanpa celah, selebar layar/jendela), digulir ke bawah. Tinggi = proporsi asli skin
(`layout.padZone`, bila tidak ada seluruh viewBox) pada lebar yang tersedia -> TIDAK di-stretch ke tinggi layar. Disarankan viewBox 1280x600.
Susunan yang dianjurkan: **baris L di atas, baris R di bawah**, tiap baris: knob GAIN di kiri + 31 slider tegak (bawah = -12 dB, atas = +12 dB, 0 dB di tengah).

## Function (hanya ini)
| function | tipe | keterangan |
|---|---|---|
| `eq31.l.01` … `eq31.l.31` | slider / rotary | band kiri, 20 Hz … 20 kHz (1/3 oktaf: 20 25 31.5 40 50 63 80 100 125 160 200 250 315 400 500 630 800 1k 1.25k 1.6k 2k 2.5k 3.15k 4k 5k 6.3k 8k 10k 12.5k 16k 20k) |
| `eq31.r.01` … `eq31.r.31` | slider / rotary | band kanan |
| `eq31.l.gain` `eq31.r.gain` | rotary / slider | knob GAIN sisi kiri / kanan (0.5 = 0 dB, ±12 dB) |
| `eq31.toggle` | toggle | efek equalizer ON/OFF |
| `eq31.mode` | toggle | mode equalizer (sembunyikan bagian ini) — opsional |
| `eq31.reset` | button | semua band & gain kembali flat |
| `meter.output` | indicator | lampu level keluaran (opsional) |
Function mixer lain juga boleh dipakai bila perlu. Nilai default semua band/gain = `0.5`. Satu function = satu kontrol per skin.

## Slider band
Grup `data-type="slider"`: `data-x1,y1` = pusat handle di nilai 0 (dB terendah, BAWAH), `data-x2,y2` = nilai 1 (ATAS); `<rect>` pertama = kotak handle (fill none).
Hit area membungkus jalur (lebar >= 30 unit; 31 band pada 1280 unit -> jarak ±36). Hit tidak boleh tumpang tindih.

## Contoh lengkap
`MIXDTX-EQSKIN/skins/eq31-dbx1231/` (dibuat oleh `gen.py`, divalidasi `validate.py`). Salin sebagai dasar skin baru, lalu hitung ulang sha256+size di `index.json`.
