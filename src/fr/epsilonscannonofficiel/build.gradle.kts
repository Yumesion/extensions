import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "Epsilon Scan (non officiel)"
    versionCode = 1
    contentWarning = ContentWarning.NSFW
    libVersion = "1.4"
    theme = "pam"

    source {
        lang = "fr"
        baseUrl = "https://epsilonscan.to"
    }
}
