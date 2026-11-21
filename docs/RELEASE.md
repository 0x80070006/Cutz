# Construire une APK release signée

## 1. Créer la clé (une seule fois)

```powershell
& "C:\Program Files\Android\Android Studio\jbr\bin\keytool.exe" -genkeypair -v `
  -keystore cutz-release.jks -alias cutz -keyalg RSA -keysize 4096 -validity 10000
```

Conservez `cutz-release.jks` et ses mots de passe **hors du dépôt** et sauvegardez-les : sans cette clé, impossible de publier une mise à jour installable par-dessus l'ancienne version.

## 2. Déclarer la clé à Gradle

Créez `keystore.properties` à la racine (ignoré par git) :

```properties
storeFile=cutz-release.jks
storePassword=...
keyAlias=cutz
keyPassword=...
```

`app/build.gradle.kts` lit ce fichier ; s'il est absent, la release est produite **non signée** (cas de F-Droid, qui signe lui-même ou vérifie un build reproductible).

## 3. Construire

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew clean assembleRelease
```

Sortie : `app/build/outputs/apk/release/app-release.apk` (R8 + réduction des ressources actifs, ≈8 Mo dont ≈4 Mo d'icônes compressées).

## 4. Vérifier

```powershell
$bt = "$env:LOCALAPPDATA\Android\Sdk\build-tools\36.0.0"
& "$bt\apksigner.bat" verify --print-certs app\build\outputs\apk\release\app-release.apk
& "$bt\aapt2.exe" dump permissions app\build\outputs\apk\release\app-release.apk
```

La seconde commande ne doit lister **aucune** permission `INTERNET` (seule apparaît la permission interne `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` ajoutée par AndroidX).

## 5. Publier

- **GitHub Releases** : joindre l'APK signée et son SHA-256 (`Get-FileHash app-release.apk`).
- **Accrescent** : publier un APK set signé (voir la documentation Accrescent ; `bundletool build-apks` à partir de `.\gradlew bundleRelease`).
- **F-Droid** : ouvrir une demande sur `fdroiddata` ; le build F-Droid n'a besoin que de `google()`/`mavenCentral()` et n'exécute pas le script d'icônes (les assets générés sont versionnés). `dependenciesInfo` est désactivé pour éviter le bloc de métadonnées signé par Google.

Pensez à incrémenter `versionCode` et `versionName` dans `app/build.gradle.kts` à chaque version.
