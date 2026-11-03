# S-Hub Store – Kurulum Rehberi

Bu klasör hazır bir Cordova projesidir. Sıra: (1) APK'yı derle, (2) Firebase'i bağla, (3) AdMob'u bağla, (4) logo/görselleri koy.

## 0) Klasörde ne var
- `www/index.html` → tüm uygulama (tek dosya)
- `local_plugins/shub-native/` → izin sayfası, indirme çubuğu, gizli klasör ve kurulum için Android eklentisi
- `.github/workflows/build.yml` → APK'yı bulutta derler (bilgisayar gerekmez)
- `config.xml` → uygulama adı / paket adı (`com.shub.store`)

## 1) APK'yı telefondan derlemek (GitHub Actions)
1. github.com'da ücretsiz hesap aç, **yeni bir repo** oluştur (ör. `shub-store`). Güncellemeler için repo **Public** olmalı (APK'nın sabit indirme adresi gerekir). İmza anahtarı repoya konmaz, aşağıda "Secrets" olarak girilir; bu yüzden public olması güvenli.
2. Bu klasörün içindekileri repoya yükle (Add file → Upload files; `.github` klasörü de gitmeli).
3. **İmza anahtarı (bir kez):** repo → Settings → Secrets and variables → Actions → **New repository secret**:
   - `KEYSTORE_B64` → sana verdiğim `keystore_b64.txt` dosyasının tüm içeriği
   - `KEYSTORE_PASSWORD` → `sifre.txt` içindeki şifre (`KEYSTORE_PASSWORD=` kısmı olmadan, sadece değer)
4. Repoda **Actions → "APK derle" → Run workflow**. ~6-8 dk sonra **Releases** sayfasında `shub-store.apk` çıkar (veya iş sayfasındaki Artifacts).
> **İmza dosyasını (`shub-release.jks`) ve şifreyi yedekle, kimseyle paylaşma.** Kaybedersen mevcut kullanıcılara güncelleme gönderemezsin (Android, farklı imzalı APK'yı "uygulama yüklenemedi" diyerek reddeder).
> İlk geçişte telefondaki eski (debug imzalı) sürümü **bir kez silip** bu sürümü kur. Bundan sonraki tüm sürümler üstüne güncellenir, veri silinmez.
> Eklentiyi ben burada derleyip çalıştıramadım. Derlemede hata olursa Actions günlüğündeki kırmızı satırı bana at.

## 2) Kayıt/giriş için Firebase (benzersiz kullanıcı adı, 7 gün isim kilidi)
Firebase olmadan uygulama yalnızca o telefonda çalışan yerel hesap kullanır; kullanıcı adı **başka telefondakilerle karşılaştırılamaz**. Gerçek benzersizlik için:
1. console.firebase.google.com → **Proje ekle** (ör. `shub-store`).
2. Proje ayarları → **Uygulamalarınız → Web (</>)** ekle → verilen `firebaseConfig` değerlerini kopyala.
3. **Build → Authentication → Sign-in method → E-posta/Şifre** → etkinleştir.
4. **Build → Firestore Database → Create database** (Production mode, bölge: eur3 veya size yakın).
5. Firestore → **Rules** sekmesine aşağıdakini yapıştırıp **Publish**:
```
rules_version = '2';
service cloud.firestore {
  match /databases/{db}/documents {
    match /usernames/{name} {
      allow read: if request.auth != null;
      allow create: if request.auth != null
        && request.resource.data.uid == request.auth.uid
        && name == name.lower();
      allow delete: if request.auth != null && resource.data.uid == request.auth.uid;
    }
    match /users/{uid} {
      allow read: if request.auth != null && request.auth.uid == uid;
      allow create: if request.auth != null && request.auth.uid == uid
        && request.resource.data.premium == false;
      allow update: if request.auth != null && request.auth.uid == uid
        && request.resource.data.premium == resource.data.premium
        && (request.resource.data.name == resource.data.name
            || (request.time > resource.data.nameChangedAt + duration.value(7, 'd')
                && request.resource.data.nameChangedAt == request.time));
    }
  }
}
```
6. `www/index.html` içinde `var CONFIG={ firebase:null,` satırını şöyle değiştir:
```
firebase:{apiKey:"...",authDomain:"...",projectId:"...",appId:"..."},
```
7. Böylece: aynı kullanıcı adı (büyük/küçük harf fark etmez) ikinci kişiye verilmez; kullanıcı adı değişince 7 gün kilitlenir. Kilit **sunucu kurallarında** da var, uygulamayı kurcalayanlar aşamaz.
8. **Premium yapmak:** Firestore → `users` → kullanıcının belgesi → `premium` alanını `true` yap (kuralları gereği kullanıcı bunu kendi değiştiremez). Premium kullanıcı reklamsız indirir ve "Dosyayı Downloads'a kaydet" butonunu görür.

Not: uygulama ilk açılışta Firebase kodunu internetten (gstatic.com) yükler. Çevrimdışı çalışsın istersen aynı dosyaları `www/` içine koyup `Backend.init` içindeki adresleri değiştirmek gerekir.

## 3) Reklam bağlama (AdMob – ödüllü reklam)
1. admob.google.com → hesap aç → **Uygulamalar → Uygulama ekle → Android** (Play'de yoksa "hayır" de). **Uygulama Kimliği**'ni al: `ca-app-pub-XXXXXXXXXXXXXXXX~YYYYYYYYYY`.
2. Uygulama içinde **Reklam birimi ekle → Ödüllü** → **Reklam birimi kimliği**'ni al: `ca-app-pub-XXXXXXXXXXXXXXXX/ZZZZZZZZZZ`.
3. Uygulama Kimliği'ni GitHub'da: repo → Settings → Secrets and variables → Actions → **Variables** → `ADMOB_APP_ID` olarak ekle (workflow bunu kullanır).
4. `www/index.html` içinde `rewardedAndroid:` değerine reklam birimi kimliğini yaz.
5. **Önce TEST kimlikleriyle dene** (dosyada hazır duruyor): reklam ekranı Google'ın "Test Ad" ibaresiyle gelmeli. Gerçek kimliğe geçince testlerde **kendi reklamına tıklama**; hesabın kapanabilir.
6. Davranış: 2 reklam izlenmeden indirme başlamaz; reklam yarıda kapatılırsa aynı reklam tekrar istenir; reklam yüklenemezse (`allowOnFail:true`) indirmeye izin verilir (kapatmak için `false`).
> Eklenti API'si sürüme göre değişebiliyor. Kod `admob.RewardedAd` (admob-plus 2.x) arıyor; bulamazsa sahte test ekranı gösterir. Telefonda Google test reklamı yerine "Reklam alanı" yazan sahte ekran görürsen bana yaz, sürüme uyarlarım.
> AdMob'da ayrıca ödeme bilgisi/vergi formu doldurman gerekir; reklam geliri ancak eşiği geçince ödenir.

## 4) İzinler ve indirme nasıl çalışıyor
- **Giriş/kayıt sonrası izin sayfası:** Bildirim izni + "bilinmeyen kaynaklardan yükleme" (Android'de o uygulamaya özel "Bu kaynaktan izin ver" anahtarı). İkisi de verilmeden "Devam et" kapalıdır; ayarlardan dönünce sayfa kendiliğinden güncellenir. Her açılışta izin geri alınmışsa sayfa yeniden çıkar.
- **İndirme:** 2 reklam → şeffaf ilerleme çubuğu %1'den %100'e → bitince Android'in kurulum ekranı otomatik açılır; buton "Kur" olur.
- **Gizli klasör:** Dosyalar uygulamanın özel alanına (`files/apks`) iner, dosya yöneticisinde görünmez. Premium kullanıcı isterse "Dosyayı Downloads'a kaydet" ile kopyalayabilir (Android 10+).
- Güvenlik notu: özel klasör cihazı root'layan biri için korumasız; amaç sıradan kullanıcıyı yönlendirmek, kopya korumak değil.
- "Yalnızca Wi-Fi ile indir" ve "Bildirimler" anahtarları şimdilik sadece tercihi saklıyor; bağlamak sonraki sürümde.

## 5) Logo ve görseller
- Uygulama ikonu için `icon.png` (512×512) koy, `config.xml` içindeki `<icon src="icon.png" />` satırının yorumunu kaldır.
- Uygulama sayfası için `www/img/` klasörü aç. `www/index.html` içinde `CATALOG` listesinde her uygulamaya şunu ekle:
  `icon:"img/schat-icon.png", shots:["img/schat-1.jpg","img/schat-2.jpg","img/schat-3.jpg"]`
- İndirme bağlantıları: `mods[...].versions[...].apk` alanına **doğrudan APK adresi** (https) yaz. Dosyayı GitHub Releases, Google Drive (doğrudan link) veya kendi sunucunda tutabilirsin.
- Görsele dokununca büyür; sağ/sol ok, kaydırma ve ✕ ile kapanır.

## 6) Yayın (release) için
- Test dışında dağıtım için **release imzası** gerekir (keystore). Workflow'u `cordova build android --release` ve secrets'taki keystore ile genişletiriz; ihtiyaç olunca söyle.
- Google Play, "başka uygulama indirip kuran" mağaza uygulamalarını (REQUEST_INSTALL_PACKAGES) genelde kabul etmez. Bu uygulama kendi sitenden/Telegram'dan dağıtım için uygundur.
- Mağazaya yalnızca **kendi uygulamalarını** ya da yayıncısının yazılı izin verdiği uygulamaları koy; başkasının oyununun modlu kopyasını reklamla dağıtmak telefon kullanıcısına değil, sana hukuki/hesap riski getirir.

## 7) Güncelleme sistemi (uygulama içinden güncelleme)
Nasıl çalışır: uygulama açılırken `version.json` dosyasına bakar; oradaki `versionCode` telefondakinden büyükse "Yeni sürüm var" penceresi çıkar → **Güncelle** → APK iner (yüzde çubuğu) → Android'in normal "Güncelle/Kur" ekranı açılır. Veriler silinmez. Ayarlar → "Güncellemeleri denetle" ile elle de bakılır.

Bir kerelik ayar: `www/index.html` içinde `update:{url:""}` satırına şunu yaz (KULLANICI ve DEPO'yu değiştir):
`update:{url:"https://raw.githubusercontent.com/KULLANICI/DEPO/main/version.json"},`
ve `version.json` içindeki `apk` alanını ekle (aynı dosyaya):
`"apk": "https://github.com/KULLANICI/DEPO/releases/latest/download/shub-store.apk"`

**Yeni sürüm yayınlama (her güncellemede):**
1. `www/index.html` (ve istediğin dosyalar) değişince repoya yükle.
2. `version.json` içinde **`versionCode`'u 1 artır** (2 → 3) ve `versionName`/`notes` yaz. (`versionCode` artmazsa Android güncellemeyi kabul etmez.)
3. Actions otomatik çalışır (push ile) ya da elle "Run workflow". Bittiğinde Releases'e yeni APK konur ve kullanıcılar güncelleme penceresini görür.
4. Zorunlu güncelleme istersen `minVersionCode`'u yeni sürüme eşitle: "Sonra" butonu kalkar.
Not: `index.html` içindeki `APP_VERSION` sadece tarayıcı testinde görünür; gerçek sürüm `version.json`'dan gelir.

"Uygulama yüklenemedi" hatasının 3 olağan nedeni: (a) farklı imza (bu yüzden sabit anahtar şart), (b) `versionCode` küçük/eşit, (c) paket adı farklı (`com.shub.store` sabit kalmalı).

## 8) Logo ve görselleri yerleştirmek (adım adım)
1. `www/img/` klasörü hazır (içindeki `README.txt` beklenen dosya adlarını listeler). Dosyaları oraya yükle: `schat-icon.png` (512×512 kare), `schat-1.jpg`, `schat-2.jpg`… (yatay 16:9 görseller banner'da en iyi durur; tam ekran telefon görüntüsü de olur).
2. `www/index.html` içinde `var CATALOG=[` bölümünde `icon:` ve `shots:` satırları **zaten ekli** (S-Chat: `schat-icon.png`, `schat-1/2/3.jpg`; Football Battle 26: `fb26-icon.png`, `fb26-1/2/3.jpg`). Yeni uygulama eklersen şu kalıbı kullan:
```
 {id:"s-chat", name:"S-Chat", ... ,
  icon:"img/schat-icon.png",
  shots:["img/schat-1.jpg","img/schat-2.jpg","img/schat-3.jpg"],
```
3. İşlem bitince `version.json` içindeki `versionCode`'u artır → Actions derler → telefonda güncelleme gelir.
Dosya yoksa uygulama yer tutucu gösterir, varsa otomatik kullanır. Detay sayfasındaki banner (3,5 sn) ve ana sayfadaki büyük öne çıkan sekme (4 sn) kendiliğinden kayar; ana sayfa sekmesi her uygulamanın ilk görselini arka plan yapar. Hiç görsel yoksa "Image 1-4" yer tutucuları kayar. Banner'a dokununca kayma durur, tıklayınca görsel büyür.
Görsellerin hakkı sende olmalı: yalnızca kendi uygulamalarının logoları/ekran görüntüleri.


## 9) Reklam: Unity Ads (AdMob yerine)
1. cloud.unity.com → Unity hesabı aç → **Monetization (Ads)** bölümünden uygulamanı **Android** olarak ekle. Mağaza bağlantısı sorarsa "henüz yayında değil" seç.
2. Uygulamanın **Game ID**'sini kopyala (sayı, örn. 1234567).
3. `www/index.html` → `CONFIG.unity.gameId:"1234567"` yaz. `placement:"Rewarded_Android"` Unity'de varsayılan ödüllü birim adıdır; panelde farklıysa onu yaz.
4. Önce `testMode:true` ile dene (test reklamı gelir). Panelde de Test Mode açık olmalı. Yayına geçerken `testMode:false` yap.
5. Derleme sırasında `com.unity3d.ads:unity-ads:4.12.5` otomatik eklenir (plugin.xml). Derleme hatası olursa Actions günlüğünü gönder.
6. Game ID boşsa uygulama deneme (5 sn) ekranı gösterir.

## 10) Abonelik (elle, ücretsiz)
1. Ödemeyi uygulama dışından al (Telegram'dan yaz, IBAN/Papara vb.). Profil → Abone ol düğmesi Telegram'ı açar.
2. Firebase Console → Firestore → `usernames/{kullanici_adi_kucuk_harf}` belgesinden `uid`'yi bul.
3. `users/{uid}` belgesinde `premium` alanını `true` yap.
4. Kullanıcı uygulamadan çıkış yapıp tekrar giriş yapınca abonelik aktif olur.
5. Aboneliği bitirmek için `premium`'u `false` yap.
