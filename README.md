# Yume Extensions

Dépôt d'extensions de sources de manga pour **Scan Yume** (fork de Mihon / Tachiyomi).

## Ajouter ce dépôt dans l'app

Dans **Scan Yume** → **Parcourir** → **Sources** → **Extensions** → **⋮** → **Dépôts d'extensions**, ajoute :

```
https://raw.githubusercontent.com/Yumesion/extensions/main/index.json
```

## Extensions disponibles

| Extension | Langue | Version | Contenu | Site |
|---|---|---|---|---|
| **Black Army** | 🇫🇷 Français | 1.6.1 | SAFE (chapitres VIP masqués) | blackarmy.fr |
| **Meraki Scans** | 🇫🇷 Français | 1.6.1 | SAFE (SFW uniquement) | merakiscans.net |
| **ScanManga (non officiel)** | 🇫🇷 Français | 1.4.5 | MIXED | scan-manga.com |
| **Solaris Scans** | 🇫🇷 Français | 1.6.2 | SAFE (SFW uniquement) | solaris-scans.fr |
| **Yurei Scan** | 🇫🇷 Français | 1.6.2 | MIXED (SFW + 18+) | yurei-scan.fr |

## Notes par extension

- **Black Army** : les chapitres « Fast Pass » (VIP) sont masqués. Le site a une recherche texte cassée (titres sans lien) → navigation par le catalogue.
- **Meraki Scans** : la recherche du site est cassée côté serveur (API `/api/search/` → 500) → navigation par le catalogue uniquement. La section 18+ (`/nsfw/`) est verrouillée derrière un login, donc inaccessible.
- **ScanManga (non officiel)** : source « non officielle » (le site casse activement les scrapers). Lecteur protégé par anti-bot LEL (fingerprint GPU + tokens) ; lecture validée sur IP française. Peut se dégrader si le site durcit sa protection.
- **Solaris Scans** : le « Mode 18+ » du site n'est pas activé → contenu classique (SFW) uniquement.
- **Yurei Scan** : contenu mixte (SFW + 18+ selon les œuvres du site).

## Structure

- `index.json` — index des extensions (format `NetworkExtensionStore`)
- `apk/` — APK compilés
- `icon-*.png` — icônes des extensions
- `src/fr/<source>/` — code source (Black Army, Meraki, Solaris, Yurei en `KeiSource` libVersion 1.6 ; ScanManga en `HttpSource` libVersion 1.4)

## Développement

Les extensions sont compilées via le build system keiyoushi (`extensions-source`).
Le code source est fourni ici pour référence et versionning.
