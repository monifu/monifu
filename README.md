Extensions to Scala's standard library for multi-threading primitives, functional programming and whatever makes life easier.

[![Build Status](https://travis-ci.org/alexandru/monifu.png?branch=master)](https://travis-ci.org/alexandru/monifu)

## Documentation

Available docs:

* [Atomic References](docs/atomic.md)

## Usage

Requires Scala 2.10 and up. From SBT, to use the latest snapshot:

```scala
resolvers += "Sonatype snapshots" at "http://oss.sonatype.org/content/repositories/snapshots/"

libraryDependencies += "org.monifu" %% "monifu" % "0.2-SNAPSHOT"
```

## License

All code in this repository is licensed under the Apache License, Version 2.0.
See [LICENCE.txt](./LICENSE.txt).