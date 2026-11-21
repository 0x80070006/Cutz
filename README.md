# Cutz

Créer, modifier et gérer des raccourcis personnalisés sur l'écran d'accueil Android.
Pensé pour un **Google Pixel 9a sous GrapheneOS** (sans services Google), compatible **Android 8+** (minSdk 26, targetSdk 36).

- 100 % hors ligne : **aucune permission INTERNET** (retirée explicitement du manifeste fusionné), aucune télémétrie, aucune dépendance Google Play Services.
- Dépendances 100 % open source, résolues depuis `google()` et `mavenCentral()` uniquement : compatible F-Droid / Accrescent / GitHub Releases.
- Material 3, couleurs dynamiques (Material You), thème clair/sombre, interface en français (anglais inclus dans `values-en/`).

## Fonctionnalités

| Onglet | Contenu |
|---|---|
| **Créer** | Cible *application installée* (liste avec recherche) ou *lien web* (http/https validé) · nom · icône issue de la banque intégrée (12 096 icônes, recherche plein texte, filtres par catégorie et par source, section « Récents »), de la galerie (Photo Picker + recadrage carré) ou icône d'origine de l'app · fond, couleur du glyphe, forme, taille, marge · aperçu en direct · bouton « Ajouter à l'écran d'accueil ». |
| **Mes raccourcis** | Sections *Actifs*, *Absents de l'accueil*, *Application désinstallée*, *À retirer manuellement de l'accueil* · modifier, dupliquer, supprimer, restaurer, ré-épingler · export / import JSON (Storage Access Framework). |
| **À propos** | Packs d'icônes avec version, licence (texte complet), lien vers le dépôt ; bibliothèques logicielles. |

### Suppression d'un raccourci

Android ne permet pas à une application de retirer une icône épinglée. Cutz applique donc :

1. `disableShortcuts(ids, "Raccourci supprimé")` : l'icône devient grise sur l'accueil.
2. Statut `DESACTIVE` / raison `DELETED_BY_USER` en base.
3. Affichage dans « À retirer manuellement de l'accueil » avec la consigne (appui long → « Retirer »).
4. Dès que le launcher ne renvoie plus l'icône dans `getPinnedShortcuts()`, la ligne passe à « Retiré de l'accueil ✓ » ; l'utilisateur peut alors la supprimer définitivement (ou à tout moment via « J'ai retiré l'icône »).

Un raccourci qui n'est pas sur l'accueil (dialogue d'épinglage annulé, import) est supprimé directement.

## Build

Prérequis : JDK 17+ (celui d'Android Studio convient), Android SDK 36.

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew assembleDebug                 # app/build/outputs/apk/debug/app-debug.apk
.\gradlew testDebugUnitTest             # tests unitaires JVM
.\gradlew connectedDebugAndroidTest     # test instrumenté (émulateur/appareil branché)
python -m unittest discover -s tools -t tools   # tests du mapping de catégories
```

Installation sur un téléphone : `adb install app/build/outputs/apk/debug/app-debug.apk` (le build debug a l'identifiant `fr.cutz.debug` et cohabite avec la release).

Release signée : voir [docs/RELEASE.md](docs/RELEASE.md).

## Architecture

```
app/src/main/java/fr/cutz/
├── CutzApp.kt              AppContainer : injection manuelle (≈10 singletons, pas besoin de Hilt)
├── data/
│   ├── db/                  Room : ShortcutEntity, ShortcutDao, CutzDatabase (schéma exporté dans app/schemas)
│   ├── ShortcutRepository   interface + implémentation Room (fake en test)
│   ├── icons/               IconIndex (recherche, pur JVM), IconCatalog (assets), SvgRenderer (AndroidSVG + LRU)
│   ├── apps/                applications lançables (visibilité via <queries>, sans QUERY_ALL_PACKAGES)
│   ├── backup/              format JSON v1, export/import SAF
│   └── GalleryStore         images recadrées copiées dans filesDir/gallery
├── shortcut/
│   ├── IconGeometry         108 dp / 72 dp visibles / 66 dp zone de sécurité, tracés des formes
│   ├── ShortcutIconFactory  bitmap adaptatif → IconCompat.createWithAdaptiveBitmap
│   ├── ShortcutPublisher    façade ShortcutManagerCompat (épinglage, update, disable/enable, limites de taux)
│   ├── ShortcutSync         réconciliation base ↔ launcher (démarrage, onResume, callback d'épinglage, désinstallation)
│   ├── LaunchActivity       trampoline : reçoit un id, lit la cible en base, lance l'app ou l'URL
│   └── Receivers            PACKAGE_FULLY_REMOVED, callback d'épinglage
├── ui/                      Compose (MVVM) : create/, shortcuts/, about/, common/, theme/
└── util/UrlValidator
```

### Décisions techniques non évidentes

- **Trampoline `LaunchActivity`** plutôt que l'intent cible dans le `ShortcutInfo` : modifier la cible ne demande aucun appel à `ShortcutManager` (pas de limite de taux), une app désinstallée ou l'absence de navigateur donnent un message clair, et l'activité exportée n'accepte qu'un identifiant (pas de redirection d'intent possible par une autre app).
- **Formes** : avec une icône adaptative, c'est le launcher qui découpe. La forme « Système » remplit les 108 dp et laisse le launcher choisir (cercle sur Pixel). Les autres formes sont dessinées sur fond transparent, dimensionnées pour que leur point le plus éloigné tombe sur le cercle de sécurité de 66 dp : aucun masque ne peut les rogner (un carré apparaît donc plus petit qu'un rond, c'est le prix à payer).
- **Taille du bitmap** : 108 dp × densité, bornée entre xxhdpi (3x) et xxxhdpi (4x). Au-delà, le système redimensionne de toute façon et la transaction Binder grossit inutilement ; les mises à jour sont envoyées par paquets de 4.
- **Limites de taux** : `isRateLimitingActive()` est testé avant `updateShortcuts`. En cas de refus, la base est mise à jour avec `pendingPush = true` et `ShortcutSync` renvoie la mise à jour au prochain passage, en un seul lot.
- **Statut après demande d'épinglage** : la ligne est enregistrée `ACTIF` avant la demande ; la synchronisation (callback du launcher, `onResume`, re-vérification différée) la passe à `NOT_PINNED` si l'utilisateur annule. Une période de grâce de 4 s évite de la marquer pendant que le dialogue est ouvert.
- **Coloration des SVG** : le script d'import garantit que chaque glyphe utilise `currentColor` (ajout de `fill="currentColor"` aux logos Simple Icons) ; au rendu, une simple substitution de texte suffit, plus fiable que l'injection CSS d'AndroidSVG.
- **Recadrage** maison (≈100 lignes Compose) et **sélecteur de couleur** HSV : une dépendance de moins à auditer.
- `allowBackup="false"` : la base n'a de sens qu'avec les icônes épinglées de l'appareil, que la sauvegarde Android ne restaure pas. L'export JSON (images de galerie incluses en base64) sert au transfert.

## Icônes

| Pack | Licence | Version figée | Icônes |
|---|---|---|---|
| [Simple Icons](https://github.com/simple-icons/simple-icons) | CC0-1.0 | 16.38.0 | 3 472 |
| [Tabler Icons](https://github.com/tabler/tabler-icons) | MIT | 3.52.0 (outline + filled) | 6 545 |
| [Lucide](https://github.com/lucide-icons/lucide) | ISC | 1.77.0 | 2 079 |

Les fichiers générés sont **versionnés** dans `app/src/main/assets/` : le build Android n'accède jamais au réseau.

### Pipeline (`tools/import_icons.py`, Python 3.10+, bibliothèque standard uniquement)

1. Télécharge les archives listées dans `tools/icons.config.json` (cache `tools/.cache/`) et **vérifie leur SHA-256**.
2. Lit les archives en mémoire (jamais de `extractall`), minifie les SVG, impose `currentColor`, valide les noms de fichiers.
3. Écrit `assets/icons/<source>/<nom>.svg`, `assets/icons/index.json` (`{id, source, name, title, tags[], category, color?}`), `assets/icons/sources.json` et les licences dans `assets/licenses/`.
4. Les doublons de nom entre sources sont conservés : l'identifiant est `<source>/<nom>` et la source est affichée sous chaque icône.

```powershell
python tools/import_icons.py --report
```

### Catégorisation unifiée

18 catégories (`tools/mappings/categories.json`, mêmes clés que `IconCategory.kt`, vérifié par les tests). Ordre de résolution (`tools/categorize.py`) :

1. `overrides.json` : correction ponctuelle par icône.
2. `simpleicons_brands.json` : marque → catégorie (sert aussi aux icônes Tabler `brand-*`).
3. `tabler.json` / `lucide.json` : catégorie d'origine → catégorie unifiée (Lucide : plusieurs catégories, choix par priorité ; les `$extends:` sont résolus).
4. `keywords.json` : règles par mots-clés, appliquées si l'étape 3 n'a rien donné ou un groupe générique (Système, Interface, Productivité, Autres).
5. Défaut : « Autres ».

Toute catégorie inconnue dans un fichier de mapping fait échouer le script (une faute de frappe ne devient pas silencieusement « Autres »).

### Mettre à jour ou ajouter un pack

- **Nouvelle version** : changer `version` et `url` dans `icons.config.json`, lancer `python tools/import_icons.py --update-hashes --report`, relire le diff de `index.json`, committer.
- **Nouveau pack** : ajouter une entrée dans `icons.config.json`, écrire une fonction `import_<id>(archive, rules, stats)` dans `import_icons.py` (l'enregistrer dans `IMPORTERS` et `LICENSE_FILES`), ajouter un mapping si le pack a des catégories, puis ajouter la valeur à l'enum `IconPack` (`IconModels.kt`). L'écran À propos lit `sources.json` automatiquement.
- **Améliorer le classement** : compléter `simpleicons_brands.json` (≈2 200 logos Simple Icons restent en « Autres » faute de catégorie officielle) ou `keywords.json`, relancer le script.

## Tests

- `tools/test_categorize.py` : mapping de catégories (règles, priorités, surcharges, cohérence avec l'enum Kotlin).
- `app/src/test` (42 tests) : `IconIndex` (parsing, recherche, filtres, validité de l'index embarqué), `UrlValidator`, format de sauvegarde, `CreateViewModel`, `ShortcutsViewModel`, `ShortcutSync` (machine à états), géométrie des formes.
- `app/src/androidTest/CreateAndPinTest` : flux complet création → épinglage (UiAutomator accepte le dialogue du launcher) → vérification dans `getPinnedShortcuts()` et en base. Ignoré si le launcher ne gère pas l'épinglage.

## Licence

Le code de Cutz est distribué sous **GNU General Public License v3.0** (voir [LICENSE](LICENSE)) : vous pouvez l'utiliser, le modifier et le redistribuer, à condition que toute version distribuée reste sous GPL-3.0 avec son code source.

Les icônes embarquées restent sous la licence de leur pack (CC0-1.0, MIT, ISC, compatibles GPL-3.0) ; leurs textes de licence sont fournis dans `app/src/main/assets/licenses/` et affichés dans l'écran « À propos ». Les logos sont des marques de leurs propriétaires respectifs.
