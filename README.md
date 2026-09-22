# EDITH OS – phase 1

Une seule APK : interface WebView, Python embarqué (Chaquopy), actions Android natives.
Pas de Termux:API, pas de serveur local.

## Compiler sans PC (GitHub Actions)

Depuis Termux, dans le dossier du projet :

    pkg install git
    git init && git add . && git commit -m "EDITH OS phase 1"
    git branch -M main
    git remote add origin https://github.com/TON_COMPTE/EdithOS.git
    git push -u origin main

GitHub demande un jeton d'accès personnel (Settings > Developer settings > Personal access
tokens) à la place du mot de passe.

Ensuite : onglet **Actions** du dépôt > « Build APK » > ouvre l'exécution terminée > télécharge
l'artefact **EdithOS-debug-apk** (un zip qui contient l'APK), extrais-le et installe l'APK.
Chaque `git push` relance la compilation. Le bouton « Run workflow » la lance à la main.

Compiler dans Termux reste possible mais fragile (AAPT2) :
`gradle assembleDebug -PbuildPython=/data/data/com.termux/files/usr/bin/python3.11`
avec `android.aapt2FromMavenOverride=/data/data/com.termux/files/usr/bin/aapt2` dans `gradle.properties`.

## Premier lancement

1. Ouvre ☰ > APIs, ajoute un fournisseur (URL de base + clé), « Chercher les modèles », choisis-en un,
   Enregistrer.
2. Écris ou parle. Un modèle marqué « Secours » ne sert que si les titulaires sont indisponibles.
3. Contrôle de l'écran (optionnel) : ☰ > Téléphone > « Infos de l'app », menu ⋮, « Autoriser les
   paramètres restreints », puis « Accessibilité » et active EDITH.

## Ce qui est en place

- Multi-API compatibles OpenAI, bascule silencieuse entre APIs proposant le même modèle,
  modèles de secours, suivi des tokens et quotas annoncés, journal.
- Masquage des numéros de carte (et e-mails, téléphones en option) avant envoi aux APIs.
- Batterie, Wi-Fi (état), position, torche, volume, SMS, lancement d'app, ouverture de lien.
- Touches, balayages, retour/accueil, lecture d'écran (AccessibilityService).
- Voix native (reconnaissance et synthèse), mémoire SQLite, clés API chiffrées (Keystore).
- **Agence d'agents** (☰ > Agence) : patron (conversation générale) + rôles spécialisés
  (analyste d'image, génération d'images, voix, transcription), chacun avec son propre modèle.
  « Configurer automatiquement » propose une équipe à partir des modèles déjà ajoutés ; un
  sélecteur au-dessus du clavier permet de parler directement à un agent plutôt qu'au patron.
- **Tâches planifiées** (☰ > Tâches) : une tâche s'exécute une fois ou tous les jours à une
  heure fixe, même écran éteint (alarme exacte + service en premier plan). Chaque étape est une
  action déjà existante (SMS, torche, toucher l'écran…) ; une vérification optionnelle contrôle
  le texte affiché à l'écran après l'étape. Les actions sensibles utilisées par une tâche sont
  approuvées une seule fois, à l'enregistrement — la tâche s'exécute ensuite sans redemander,
  y compris de nuit, mais seulement pour ce qui a été explicitement autorisé.

## Sécurité

- Aucune action sensible sans confirmation native (SMS, touches, balayages, lecture d'écran).
  Assouplir un niveau demande une confirmation à l'écran : l'IA ne peut pas le faire seule.
- La page n'accepte aucune navigation externe et n'a aucun accès réseau (CSP) : tout passe par le pont.
- Aucune sauvegarde Android (allowBackup désactivé), permissions réduites au nécessaire.

## Pas encore fait (phases suivantes)

Boucle IA qui pilote le téléphone, planificateur de tâches, recettes hors-ligne, agents et patron,
profils, modules téléchargeables, IA locale, Labo Linux, plugins.
