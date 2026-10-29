<div align="center">

# 📝 Catatan Perubahan · Changelog

**🌐 Bahasa / Language:** ketuk bagian di bawah untuk membuka / tap a section below to expand it

[⬅️ README (ID)](README.md) · [⬅️ README (EN)](README.en.md)

</div>

---

## ✦ v2.4 — 29 Oktober 2026 / 29 October 2026

<details open>
<summary><h2>🇮🇩 Bahasa Indonesia</h2></summary>

- Hasil rute tanpa pemborosan: rute sama dengan halte lebih jauh/lebih lama dibuang, rute bolak-balik dibuang
- Bus langsung: penyebab penolakan server ditampilkan dengan benar

</details>

<details>
<summary><h2>🇬🇧 English</h2></summary>

- No wasteful results: same-route options with worse stops and back-and-forth routes removed
- Live buses: server refusal reason shown correctly

</details>

---

## ✦ v2.3 — 29 Oktober 2026 / 29 October 2026

<details open>
<summary><h2>🇮🇩 Bahasa Indonesia</h2></summary>

- Bus langsung: otomatis beralih ke topik umum bila langganan per rute diputus server; saringan rute di HP; penyebab gagal + tahapnya; info pemakaian kuota
- Tarif LRT Jabodebek mengikuti jam: jam sibuk maks. Rp20.000, di luar jam sibuk & akhir pekan maks. Rp10.000

</details>

<details>
<summary><h2>🇬🇧 English</h2></summary>

- Live buses: automatic fallback to the general topic, on-phone route filtering, failure stage and data usage shown
- Time-based LRT Jabodebek fares (peak max Rp20,000, off-peak/weekend max Rp10,000)

</details>

---

## ✦ v2.2 — 29 Oktober 2026 / 29 October 2026

<details open>
<summary><h2>🇮🇩 Bahasa Indonesia</h2></summary>

### 🐞 Perbaikan

- Bus langsung tersambung lewat koneksi native Android; bila gagal, tampil penyebab dan tombol **Lihat di aplikasi TJ**
- Pola memutar di GTFS resmi (184 dari 240 rute) dipecah menjadi dua arah utama; pola lain menjadi varian berketerangan

### ⚡ Peningkatan

- Pencarian rute 3–7× lebih cepat dengan data resmi
- Pemantau perubahan rute (GitHub Actions) aktif dan mencatat status; ringkasan GTFS di cabang `gtfs-data`

</details>

<details>
<summary><h2>🇬🇧 English</h2></summary>

- Live buses connect via a native Android connection, with a clear error and an **Open TJ app** fallback
- Loop-shaped official GTFS patterns split into two main directions with described variants
- Route search 3–7× faster; route-change watcher running with status file and `gtfs-data` branch

</details>

---

## ✦ v2.1 — 29 Oktober 2026 / 29 October 2026

<details open>
<summary><h2>🇮🇩 Bahasa Indonesia</h2></summary>

### ✨ Baru

- **Posisi bus langsung** (Transjakarta, Mikrotrans, Royaltrans) dari feed armada Transjakarta: layar **Bus di sekitarmu**, perkiraan **bus berikutnya** di halte naik pada detail perjalanan, dan semua bus rute di detail rute
- **Keterangan varian** yang jelas: via Tol, lewat X, tanpa X, mulai X, putar balik di X

### ⚡ Peningkatan

- Nama trayek resmi (mis. 9N Pinang Ranti – Simpang Cawang); rute memutar dipecah menjadi dua arah
- Daftar rute: label 24 jam rata kanan, nama rute tidak terpotong
- Gambar rute: ikon orang berjalan untuk langkah jalan kaki
- Logo dengan gradasi biru yang lebih halus

</details>

<details>
<summary><h2>🇬🇧 English</h2></summary>

### ✨ New

- **Live bus positions** (Transjakarta, Mikrotrans, Royaltrans): **Buses near you**, next-bus ETA on trip details, and all buses on route details
- Clear **variant descriptions**: via toll, via X, skips X, starts at X, turns back at X

### ⚡ Improvements

- Official route names (e.g. 9N Pinang Ranti – Simpang Cawang); loop routes split into two directions
- Route list: 24-hour label right-aligned, names no longer truncated
- Route image: walking icon for walking steps
- Smoother blue logo gradient

</details>

---

## ✦ v2.0 — 29 Oktober 2026 / 29 October 2026

<details open>
<summary><h2>🇮🇩 Bahasa Indonesia</h2></summary>

### ✨ Baru

- **Bagikan rute sebagai gambar**: tombol *Bagikan rute* di setiap kartu hasil dan di detail perjalanan. Yang terkirim adalah gambar berisi asal → tujuan, total waktu & tarif, strip moda, serta jam naik, arah, tempat turun, dan tarif tiap langkah
- **Layar sambutan** dan **"Satu langkah lagi"** saat pertama dibuka, masing-masing muat dalam satu layar tanpa scroll: izin lokasi, notifikasi, berjalan di latar belakang, dan pintasan *Mulai otomatis* untuk Xiaomi, Oppo, Vivo, Realme, dan Infinix
- **Perubahan rute terdeteksi tiap jam**: GitHub Actions memeriksa GTFS resmi Transjakarta tiap jam dan menulis `data/route_changes.json`; aplikasi membaca umpan ini serta tanda versi file GTFS tiap jam, lalu memberi notifikasi dan memperbarui data rute

### 💸 Tarif

- SH2 Blok M – Bandara Soetta Rp15.000 (sejak 1 Sep 2026); SH1 dan Transjabodetabek lain tetap Rp3.500
- Hub Connector (HC2 PIK 2 – Dukuh Atas) Rp20.000
- MRT dihitung per stasiun: Rp3.000 + Rp1.000 per stasiun, maks. Rp14.000
- KA Bandara: Duri Rp70.000; Manggarai – BNI City Rp10.000
- Whoosh mulai Rp250.000 (tarif dinamis)
- Catatan penyesuaian tarif Transjakarta 1 Januari 2027 dan Perpres ojol 27/2026

</details>

<details>
<summary><h2>🇬🇧 English</h2></summary>

### ✨ New

- **Share routes as images** from every result card and the trip screen
- **Welcome** and **"One more step"** screens on first launch, each fitting on one screen: location, notifications, background running, and an *Auto-start* shortcut for Xiaomi, Oppo, Vivo, Realme and Infinix
- **Hourly route-change detection**: GitHub Actions checks the official Transjakarta GTFS every hour and writes `data/route_changes.json`; the app reads this feed and the GTFS file signature every hour, then notifies you and refreshes route data

### 💸 Fares

- SH2 Blok M – Soekarno-Hatta Airport Rp15,000 (since 1 Sep 2026); SH1 and other Transjabodetabek routes stay at Rp3,500
- Hub Connector (HC2 PIK 2 – Dukuh Atas) Rp20,000
- MRT priced per station: Rp3,000 + Rp1,000 per station, max Rp14,000
- Airport Rail: Duri Rp70,000; Manggarai – BNI City Rp10,000
- Whoosh from Rp250,000 (dynamic pricing)

</details>

---

## ✦ v1.8 — 29 Oktober 2026 / 29 October 2026

<details>
<summary><h2>🇮🇩 Bahasa Indonesia</h2></summary>

- **Mode pendampingan GPS**: posisi di peta, sisa pemberhentian, peringatan "Siap-siap turun" dan "Turun sekarang" dengan getar, tetap berjalan saat layar mati
- **Filter hasil**: urutkan Tercepat/Termurah/Minim transit dan pilih moda (opsi yang mengandung moda terpilih)
- **Pembaruan rute harian** dengan catatan perubahan (rute baru, dihapus, halte berubah)
- Membaca lokasi dari Google Maps lebih cepat (beberapa cara berjalan bersamaan + cache)
- Rute duplikat dihapus; tema biru muda dan logo gradasi biru

</details>

<details>
<summary><h2>🇬🇧 English</h2></summary>

- **GPS guidance mode** with stops left, "Get ready" and "Get off now" alerts, working with the screen off
- **Result filters** for sort order and modes
- **Daily route updates** with a change log
- Faster Google Maps location reading; duplicate routes removed; light-blue theme and new logo

</details>

---

## ✦ v1.7 — Oktober 2026 / October 2026

<details>
<summary><h2>🇮🇩 Bahasa Indonesia</h2></summary>

- Tarif TJ berbasis halte: pindah di dalam halte gratis, turun di luar halte bayar lagi
- 9 dan 9A sebagai opsi terpisah; varian **koridor 9 via Tol** (tidak berhenti di Kemanggisan s.d. Jembatan Dua)
- Penjelajahan semua kombinasi dua rute (mis. 9/9C → Makasar → JAK36/JAK108, LRT → TMII → JAK36)
- Rute memutar ditangani benar; tidak ikut bus memutar demi jalan kaki lebih pendek
- GTFS resmi Transjakarta diunduh otomatis

</details>

<details>
<summary><h2>🇬🇧 English</h2></summary>

- Station-based TJ fares; routes 9 and 9A as separate options; corridor 9 via-toll variant
- Every two-route combination explored; loop routes handled correctly
- Official Transjakarta GTFS downloaded automatically

</details>

---

## ✦ v1.0 – v1.6 — Oktober 2026 / October 2026

<details>
<summary><h2>🇮🇩 Bahasa Indonesia</h2></summary>

- Rilis awal: perencana rute multimoda offline (Transjakarta, Mikrotrans, Royaltrans, KRL, MRT, LRT, KA Bandara, Whoosh, ojol)
- Info rute dengan urutan halte dan rute sambungan
- Jam berangkat/tiba dengan jam operasional tiap moda (AMARI 24 jam)
- Input alamat lewat Google Maps (Bagikan ke Lumi), CARTO Basemaps API key di Pengaturan
- Kombinasi Mikrotrans + LRT dan semua opsi angkutan umum

</details>

<details>
<summary><h2>🇬🇧 English</h2></summary>

- First releases: offline multimodal planner, route info, operating hours, Google Maps address input, CARTO Basemaps key setting

</details>
