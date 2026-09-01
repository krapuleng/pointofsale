# BIZAPP POS

A touch-screen point-of-sale application for Windows and macOS, forked from NORD POS /
Openbravo POS 3. Version 4.0 - a Java Swing desktop application with a bundled Apache Derby
database, so there is no separate database server to install.

## What you get

- Touch-screen sales with an on-screen receipt preview, so you can trade before you own a printer
- A bundled Apache Derby database that needs no setup and listens on 127.0.0.1 only
- Products, categories, taxes, customers, stock locations and cash sessions
- Reports and receipts built on JasperReports, compiled from their `.jrxml` sources at run time
- Pluggable receipt printer, customer display, scale, label printer, fiscal printer and payment
  gateway drivers, discovered through the Java `ServiceLoader` mechanism
- A role-based user system with four seeded roles (Administrator, Manager, Employee, Guest)

## Install

You need a JDK 11 to 24 to build the application - a Java runtime alone is not enough.
Get one from <https://adoptium.net>.

macOS:

```sh
git clone https://github.com/krapuleng/pointofsale.git && cd pointofsale && ./install.command
```

Windows:

```bat
git clone https://github.com/krapuleng/pointofsale.git
cd pointofsale
install.cmd
```

If you downloaded a ZIP instead of cloning, the executable bit is lost - run
`sh install.command` on macOS.

Useful flags: `--launch` starts the app when the install finishes, `--clean` rebuilds from
scratch, and `--jdk <java_home>` picks a specific JDK - that one works on both platforms, with
`BIZAPP_JDK_HOME` and `JAVA_HOME` as the matching environment variables. macOS additionally
has `--dest <dir>`, which installs the `.app` somewhere other than `~/Applications`
(environment variable `BIZAPP_APP_DEST`); Windows has no equivalent, because the Start Menu
and Desktop shortcut locations are fixed. `--help` lists them all.

**Full instructions, troubleshooting and uninstall: [INSTALL.md](INSTALL.md)**

## First run

1. Click **Yes** when asked whether to **create** the database.
2. Click **Administrator** to log in - there is no password yet.
3. Set passwords immediately under **Maintenance -> Users**; all four seeded accounts start empty.

> **Already have a database from an older version?** The first dialog will offer to **upgrade**
> it instead, and that upgrade is irreversible and deletes every parked ticket. Copy
> `~/.derby-db` (macOS) or `%USERPROFILE%\.derby-db` (Windows) somewhere safe *before* you
> launch. See [INSTALL.md section 7](INSTALL.md#7-database-notes).

## Building from source

Neither NetBeans nor Apache Ant is required, and neither is used. Run `sh install/build.sh`
on macOS or Linux, or `powershell -NoProfile -ExecutionPolicy Bypass -File install\build.ps1`
on Windows, and the build engine produces `dist/nordpos.jar`. See
[INSTALL.md](INSTALL.md#11-building-from-source-for-developers) for the flags, the environment
variables and the exit codes.

## Project layout

- `src-beans`, `src-data`, `src-peripheral`, `src-pos`, `src-server`, `src-sync` - Java sources
- `lib/` - bundled third-party libraries; `lib-jdbc/` - database drivers, loaded at run time
- `reports/` - JasperReports report definitions
- `locales/` - translatable message bundles
- `templates/` - printer and receipt templates plus their XML schema
- `transformations/` - Pentaho Kettle definitions for CSV import and export
- `services/` - `ServiceLoader` registrations for the peripheral and payment drivers
- `install/` - the cross-platform build engine and the Windows and macOS installers

Note: `dist - bizpapp/` and `dist.rar` are stale prebuilt artifacts kept only for reference -
they are missing the peripheral driver registrations and should not be used.

## License

Copyright © 2007-2009 Openbravo, S.L. http://www.openbravo.com/product/pos

NORD POS is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.

NORD POS is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License for more details.

You should have received a copy of the GNU General Public License along with NORD POS.  If not, see http://www.gnu.org/licenses/.
