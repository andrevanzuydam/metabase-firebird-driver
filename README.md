# Firebird driver for metabase

This driver enables metabase to connect to [FirebirdSQL](https://firebirdsql.org/) databases.

## Installation:

* Make sure you have installed a recent Metabase Version.
* Download the [latest release](https://github.com/andrevanzuydam/metabase-firebird-driver/releases/latest) of the Firebird driver or [build it from source](#building-from-source).
* Create the `plugins` directory if it doesn't already exist. By default that directory is next to the metabase.jar file, but you can specify a different directory by setting the environment varianble `MB_PLUGINS_DIR`.
* Just drop the `firebird.metabase-driver.jar` in the plugins directory. On startup, metabase will load the plugin and the driver should be available.

## Authentication issues when using legacy Firebird (2.5 and older)

Example of connection string to connect to Firebird 2.5 (Protocol 12)

```
jdbc:firebirdsql://hostname:3050//var/lib/firebird/DATA.FDB?user=sysdba&password=masterkey&enableProtocol=12
```

| Firebird Version   | Protocol Version |
|--------------------|------------------|
| Firebird 1.0 - 2.0 | 10               |
| Firebird 2.1       | 11               |
| Firebird 2.5       | 12               |
| Firebird 3.0       | 15               |
| Firebird 4.0       | 16               |
| Firebird 5.0       | 19               |


If you cannot get it working, please raise an issue be sure to include the version of metabase & firebird you are having the issue with.


## Building from source:

For a detailed description, take a look at the [official documentation](https://www.metabase.com/docs/latest/developers-guide/drivers/start.html).

* Checkout the main metabase repository and the firebird driver repository in the same parent directory:
```
workspace
  - metabase
  - metabase-firebird-driver
```
* Run the `build.sh` script from the metabase-firebird-driver repository
```
cd metabase-firebird-driver
./build.sh
```
* The driver will now be built. The .jar file can be found in the `target` directory.

# Development Notes

Under WSL after cloning the metabase project and installing clojure and yarn set up your ```~/.clojure.edn```

```edn
{
  :aliases {
    ;; Add cross-project aliases here
    ;; ~/.clojure/deps.edn
    :user/firebird-driver {
      :extra-deps {metabase/firebird-driver {:local/root "../metabase-firebird-driver"}}
      :jvm-opts   ["-Dmb.dev.additional.driver.manifest.paths=../metabase-firebird-driver/resources/metabase-plugin.yaml"]
    }
  }
}

```

Run a development environment
```bash
clojure -M:user/firebird-driver:nrepl --bind 0.0.0.0 --port 50605
```
```bash
clojure -M:user/firebird-driver:run
```

# Release notes

### Current version: 1.6.2

### Version 1.6.2
- Fixes for Concat and long field names breaking queries

### Version is 1.6.1
- Added ability to use a connection string
- Has fixes for group by, complex date handling


**Sponsored with 🩵 by Code Infinity**

[<img src="https://codeinfinity.co.za/wp-content/uploads/2025/09/c8e-logo-github.png" alt="Code Infinity" width="100">](https://codeinfinity.co.za/about-open-source-policy?utm_source=github&utm_medium=website&utm_campaign=opensource_campaign&utm_id=opensource)

*Supporting open source communities <span style="color: #1DC7DE;">•</span> Innovate <span style="color: #1DC7DE;">•</span> Code <span style="color: #1DC7DE;">•</span> Empower*
