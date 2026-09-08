# Yume Extensions

Dépôt d'extensions pour **Scan Yume** (fork de Mihon / Tachiyomi).

## Ajouter ce dépôt dans l'app

Dans **Scan Yume** → **Parcourir** → **Sources** → **Extensions** → **⋮** → **Dépôts d'extensions**, ajoute :

```
https://raw.githubusercontent.com/Yumesion/extensions/main/index.json
```

## Extensions disponibles

| Extension | Langue | Version | Contenu |
|---|---|---|---|
| **Solaris Scans** | 🇫🇷 Français | 1.6.1 | MIXED (SFW + 18+ actif) |
| **Yurei Scan** | 🇫🇷 Français | 1.6.2 | MIXED (SFW + 18+) |

## Structure

- `index.json` — index des extensions (format `NetworkExtensionStore`)
- `apk/` — APK compilés
- `icon-solaris.png`, `icon-yurei.png` — icônes des extensions
- `src/fr/solarisscans/`, `src/fr/yureiscan/` — code source (pattern `KeiSource`, libVersion 1.6)

## Développement

Les extensions sont compilées via le build system keiyoushi (`extensions-source`).
Le code source est fourni ici pour référence et versionning.

### Note 18+ (Solaris Scans)

Le site Solaris Scans masque son contenu adulte derrière un « Mode 18+ » (désactivé par défaut).
L'extension envoie en permanence le cookie `solaris_adult_mode=1` pour activer ce mode côté serveur,
afin d'afficher l'intégralité du catalogue (SFW + 18+).
