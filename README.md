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
| **Yurei Scan** | 🇫🇷 Français | 1.6.2 | MIXED (SFW + 18+) |

## Structure

- `index.json` — index des extensions (format `NetworkExtensionStore`)
- `apk/` — APK compilés
- `src/fr/yureiscan/` — code source (pattern `KeiSource`, libVersion 1.6)

## Développement

L'extension est compilée via le build system keiyoushi (`extensions-source`).
Le code source est fourni ici pour référence et versionning.
