# Roughly Enough Items

[![CurseForge](https://img.shields.io/curseforge/dt/310111?logo=curseforge&label=CurseForge)](https://www.curseforge.com/minecraft/mc-mods/roughly-enough-items)
[![Modrinth](https://img.shields.io/modrinth/dt/nfn13YXA?logo=modrinth&label=Modrinth)](https://modrinth.com/mod/rei)
[![Crowdin](https://badges.crowdin.net/roughly-enough-items/localized.svg)](https://crowdin.com/project/roughly-enough-items)

Roughly Enough Items (REI) is a Minecraft mod for browsing items and recipes.
The current branch targets Minecraft 26.2 on Fabric and NeoForge.

Download REI from [CurseForge](https://www.curseforge.com/minecraft/mc-mods/roughly-enough-items)
or [Modrinth](https://modrinth.com/mod/rei). The required dependencies are listed on
each download page.

![The REI item browser](https://i.imgur.com/eQsWDrM.png)

![An REI recipe display](https://i.imgur.com/OcOQLip.png)

## Installation

Install the REI build matching both your Minecraft version and mod loader. REI has
client and server components; install it on both sides for the complete feature set.

## Development

### Requirements

- JDK 25 or newer
- The repository's Gradle wrapper; a system Gradle installation is not required

Build every active platform:

```shell
./gradlew build
```

Platform jars are written to `fabric/build/libs` and `neoforge/build/libs`.

## Using the API

Published versions are available from the Shedaniel Maven repository:

```gradle
repositories {
    maven { url = "https://maven.shedaniel.me" }
}
```

Replace `VERSION` below with an REI version compatible with your target Minecraft
version. Compile against the API and use the full platform artifact at runtime.

### Fabric

```gradle
dependencies {
    modCompileOnly "me.shedaniel:RoughlyEnoughItems-api-fabric:VERSION"
    modRuntimeOnly "me.shedaniel:RoughlyEnoughItems-fabric:VERSION"
}
```

To compile against REI's built-in display and category implementations, add:

```gradle
dependencies {
    modCompileOnly "me.shedaniel:RoughlyEnoughItems-default-plugin-fabric:VERSION"
}
```

### NeoForge

```gradle
dependencies {
    modCompileOnly "me.shedaniel:RoughlyEnoughItems-api-neoforge:VERSION"
    modRuntimeOnly "me.shedaniel:RoughlyEnoughItems-neoforge:VERSION"
}
```

To compile against REI's built-in display and category implementations, add:

```gradle
dependencies {
    modCompileOnly "me.shedaniel:RoughlyEnoughItems-default-plugin-neoforge:VERSION"
}
```

### Common Architectury code

```gradle
dependencies {
    modCompileOnly "me.shedaniel:RoughlyEnoughItems-api:VERSION"
}
```

If common code interacts with the built-in plugin, add:

```gradle
dependencies {
    modCompileOnly "me.shedaniel:RoughlyEnoughItems-default-plugin:VERSION"
}
```

The platform subprojects still need the corresponding full REI artifact as a runtime
dependency.

### Published artifacts

| Artifact | Contents |
| --- | --- |
| `RoughlyEnoughItems-api` | Common API |
| `RoughlyEnoughItems-default-plugin` | Common built-in plugin API |
| `RoughlyEnoughItems-runtime` | Common runtime |
| `RoughlyEnoughItems-api-fabric` | Fabric API |
| `RoughlyEnoughItems-default-plugin-fabric` | Fabric built-in plugin API |
| `RoughlyEnoughItems-runtime-fabric` | Fabric runtime |
| `RoughlyEnoughItems-fabric` | Complete Fabric mod |
| `RoughlyEnoughItems-api-neoforge` | NeoForge API |
| `RoughlyEnoughItems-default-plugin-neoforge` | NeoForge built-in plugin API |
| `RoughlyEnoughItems-neoforge` | Complete NeoForge mod |

All coordinates use the `me.shedaniel` group.

## Translations

Help translate REI on [Crowdin](https://crowdin.com/project/roughly-enough-items).

## License

Roughly Enough Items is licensed under the [MIT License](LICENSE).
