# Installing BIZAPP POS

## 1. What this is

BIZAPP POS is a touch-screen point-of-sale application for shops and restaurants, forked
from NORD POS / Openbravo POS 3. This is version 4.0: a Java desktop application that runs
on Windows and macOS and stores its data in a bundled Apache Derby database, so there is no
separate database server to install.

This guide takes you from a fresh copy of the source code to a running till. You do not need
to be a programmer to follow it.

---

## 2. Before you start

**You need a JDK (Java Development Kit), version 11 or newer.**

BIZAPP POS is not shipped as a pre-built download - the installer compiles it on your own
machine. Compiling needs `javac` and `jar`, and those two programs come **only** with a JDK.
A JRE (Java Runtime Environment) is *not* enough to install. Once installed, a plain JRE is
enough to *run* the application day to day.

| | Supported |
|---|---|
| Java 11 through 24 | Yes - tested |
| Java 8, 9, 10 | No - the installer stops with a clear message |
| Java 25 and newer | Untested. The installer warns and continues, but it may fail to compile: the application still uses the old `java.applet` API, which Java is in the process of removing. |

Install a JDK if you do not have one:

```sh
# macOS
brew install --cask temurin@21
```

```powershell
# Windows
winget install --id EclipseAdoptium.Temurin.17.JDK -e
```

Or download an installer from <https://adoptium.net> on either platform.

**If you do not, the installer will offer to do it for you - and it asks first.** On macOS,
when no JDK is found and Homebrew is present, the installer offers to run
`brew install --cask temurin@21`. On Windows, when no JDK is found, setup offers Eclipse
Temurin 17 through winget or Chocolatey (that is what `--install-jdk` pre-approves). Both
offers install software system-wide, so both may ask for an administrator password - the
macOS one is asked for by Homebrew, not by this installer. Both default to **no**; declining
stops with exit code 9 and changes nothing on your machine, and `--yes` accepts them without
asking. Installing a JDK yourself first is the way never to see either prompt. See section 12
for the full list of actions that can need administrator rights.

**Disk space and download.** The download is about 125 MB and can take several minutes on a
slow connection. After it finishes, the folder on disk is about 330 MB, and about 350 MB once
you have built the application. Do **not** use `git clone --depth 1` to speed it up: almost
all of the size is the current files (the bundled Java libraries and a large legacy archive),
not the history, so a shallow clone saves well under a megabyte and costs you the ability to
update normally.

**Also useful:** `git`, to download the source. If you do not have git you can download a ZIP
from GitHub instead, but see the note about lost file permissions in section 9.

---

## 3. Install

### macOS

```sh
git clone https://github.com/krapuleng/pointofsale.git && cd pointofsale && ./install.command
```

You can also open the `pointofsale` folder in Finder and **double-click `install.command`**.

If you downloaded a ZIP from GitHub instead of using git, the executable permission is lost
and `./install.command` will say *Permission denied*. Run it as an argument to the shell
instead - this works whether or not the permission survived:

```sh
sh install.command
```

Everything the installer runs afterwards is invoked through the shell in the same way, so
`sh install.command` is enough on its own; you do not have to chase the permission bit
through `install/`. If you would also like to be able to double-click it in Finder, restore
the bit once with `chmod +x install.command`.

### Windows

```bat
git clone https://github.com/krapuleng/pointofsale.git
cd pointofsale
install.cmd
```

You can also open the `pointofsale` folder in File Explorer and **double-click `install.cmd`**.

Do not try to run the `.ps1` files directly by double-clicking them - Windows opens `.ps1`
files in Notepad by default. Always start from `install.cmd`.

### Useful flags

Both installers accept the same style of options. Pass them after the command, for example
`./install.command --launch` or `install.cmd --clean --launch`.

| Flag | What it does |
|---|---|
| `--launch` | Start BIZAPP POS as soon as the install finishes |
| `--no-shortcut` (macOS) | Build only; do not create the application launcher |
| `--no-shortcuts` (Windows) | Build only; do not create Start Menu / Desktop shortcuts |
| `--clean` | Delete previous build output and rebuild everything from scratch |
| `--dest <dir>` (macOS) | Install the application bundle into `<dir>` instead of `~/Applications` |
| `--jdk <java_home>` | Build and launch with this specific JDK |
| `--repair-laf` | Fix a saved settings file that stops the app from starting (see section 8) |
| `--yes` | Answer "yes" to the consent prompts, for unattended use |
| `--quiet` | Print less progress detail |
| `--help` | Show all options |
| `--version` | Print the installer version |

Two more, for people who know they need them: `--build-dir <path>` and `--dist-dir <path>`
send the build output somewhere other than `build/` and `dist/`. macOS additionally has
`--system`, which installs into `/Applications` for all users instead of your own
`~/Applications` - it needs an administrator password and will ask first. Windows
additionally has `--install-jdk`, which lets the installer install a JDK for you through
winget or Chocolatey - it also asks first, because that changes your whole machine.

**`--clean` deletes only what the build engine wrote.** It removes six things by name from
the build and dist directories - `bizapp-classes/` and everything inside it, `javac.args`,
`MANIFEST.MF`, `classpath.txt`, `build-info.txt` and `nordpos.jar` - and then removes those
two directories themselves **only if they are empty** once those names are gone.

One of those six is a directory, and it is deleted recursively: `bizapp-classes/`, where the
compiler writes its output. That is why it carries an unusual name rather than the obvious
`classes` - it is a name the build engine owns by construction, so a recursive delete of it
cannot reach a folder you created. Every *plain* build removes it too, not only `--clean`.
Nothing else is ever deleted recursively, and no file the engine did not write is deleted at
all. So
`install.cmd --clean --dist-dir "%USERPROFILE%\Documents"` deletes those names from your
Documents folder if an earlier build put them there, leaves every other file in Documents
exactly where it was, and still succeeds - a directory that also holds your own files is not
an error. (A `--clean` *build* then recreates `build/` and `dist/` immediately and fills them
again; the removal is the first step of the rebuild, not the end of it.)

Separately, and whether or not you pass `--clean`, the engine **refuses to use** the
repository root, your home directory, `/`, any directory that contains the checkout, or
anything inside `dist - bizpapp` as a `--build-dir` or `--dist-dir`. It stops with exit code
2 and names the directory. Even with all of that, give the build a directory of its own:
mixing your files with build output is confusing however careful the deletion rule is.

**`--dest <dir>` (macOS only).** Puts `BIZAPP POS.app` in a directory of your choosing instead
of `~/Applications`. It needs no administrator rights, and it is the way to try an install
without touching `~/Applications`:

```sh
sh install.command --dest "$HOME/bizapp-test"
```

The environment variable `BIZAPP_APP_DEST` does the same thing, and the flag wins over it.
`--dest` cannot be combined with `--system`. If you install this way, pass the **same**
`--dest` to the uninstaller (section 10) - it looks in `~/Applications` and `/Applications`
by default and will not find a bundle you put somewhere else. Windows has no equivalent: the
Start Menu and Desktop shortcut locations are fixed.

### Choosing which JDK is used

If you have more than one JDK installed you can say which one to build and run with. Both
installers (`install.command`, `install.cmd`) and both ports of the build engine
(`install/build.sh`, `install\build.ps1`) apply the **same** order of precedence and stop at
the first entry that names a usable JDK - a directory containing `bin/javac`
(`bin\javac.exe` on Windows), version 11 or newer:

1. the `--jdk <java_home>` flag;
2. the `BIZAPP_JDK_HOME` environment variable;
3. the `JAVA_HOME` environment variable;
4. automatic discovery.

An explicit choice from steps 1 to 3 is either used or reported as unusable - it is never
quietly replaced by a different JDK. The installers name the JDK they settled on and where
the choice came from, for example
`==> Using JDK 17 at /Library/Java/... (from BIZAPP_JDK_HOME)`; the build engine prints the
same line without the source.

Only step 4 goes looking on its own, and the two platforms pick differently: macOS takes the
**highest** major version it can find, while Windows prefers the long-term-support releases
in the order 17, 21, 11 and only then falls back to the highest of whatever is left. So on a
machine with both Java 26 and Java 17 installed, doing nothing gets you 26 on macOS and 17 on
Windows. Any of these pins it explicitly:

```sh
sh install.command --jdk /Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home
BIZAPP_JDK_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home sh install.command
```

```bat
install.cmd --jdk "C:\Program Files\Eclipse Adoptium\jdk-17"
```

Point the value at the directory that *contains* `bin/javac`, not at `javac` itself.

The JDK chosen here is also written into the launcher the installer creates - the macOS
`.app` and the Windows Start Menu and Desktop shortcuts - so it is the JVM the application
runs on afterwards, not just the one that compiles it. Two exceptions: setting
`BIZAPP_JAVA_HOME` overrides it at launch time on both platforms, and
`install\windows\run.cmd` does not read the recorded value at all - it uses
`BIZAPP_JAVA_HOME`, then `JAVA_HOME`, then the first `java.exe` on `PATH`.

The installers are safe to run again. Re-running is the normal way to rebuild after pulling
new code, and it never touches your settings or your sales data.

---

## 4. What gets installed where

Nothing is installed system-wide, and a normal run needs no administrator password on either
platform. The exceptions are the three actions listed in section 12, all of which ask first:
macOS `--system`, the macOS offer to install a JDK with Homebrew, and Windows `--install-jdk`.

| | macOS | Windows |
|---|---|---|
| Launcher | `~/Applications/BIZAPP POS.app` | "BIZAPP POS" in your Start Menu and on your Desktop |
| Built application | `<checkout>/dist/nordpos.jar` | `<checkout>\dist\nordpos.jar` |
| Intermediate build files | `<checkout>/build/` | `<checkout>\build\` |
| Logs | `~/Library/Logs/BIZAPP POS/` | `%LOCALAPPDATA%\BIZAPP POS\logs` |
| Your settings | `~/nordpos.properties` | `%USERPROFILE%\nordpos.properties` |
| Your database | `~/.derby-db` | `%USERPROFILE%\.derby-db` |

The macOS `.app` is tiny - about 300 KB. It is a launcher, not a copy of the program: it
points at the folder you cloned. The Windows shortcuts work exactly the same way.

> **Important:** because the launcher points at the checkout, **moving, renaming or deleting
> that folder breaks BIZAPP POS.** If you need to move it, move it first and then run the
> installer again from its new location. That is a fast operation and it fixes everything.

There is also a console launcher on Windows at `install\windows\run.cmd`. Use it when the
shortcut seems to do nothing - it runs in a visible window so you can read the error.

**The database location is fixed and cannot be moved.** The application sets its own Derby
home at startup, from the Java `user.home` property, and it overrides anything a launcher or
a command line tries to set. `.derby-db` therefore always lands directly in your home folder
- `~/.derby-db` on macOS, `%USERPROFILE%\.derby-db` on Windows - on every launch path
(the `.app`, the Start Menu and Desktop shortcuts, `run.cmd`, and a bare
`java -jar dist/nordpos.jar`). There is no `-Dderby.system.home` or environment variable that
will relocate it; moving the database means moving the whole home directory. Back it up where
it is (section 6).

---

## 5. First run

On a brand-new machine BIZAPP POS opens with a Yes/No dialog about the database - but that
is not the only dialog it can show, and the other one is destructive. Which one you get
depends on what is already in `~/.derby-db` (macOS) or `%USERPROFILE%\.derby-db` (Windows):

| State of `.derby-db` | What happens on launch |
|---|---|
| Does not exist | "A working database cannot be detected... will be created" - answer Yes |
| Exists, already at version 4.0 | No dialog; you go straight to the login screen |
| Exists, at an older version with an upgrade script | "DATA MAY BE LOST. FIRST CREATE A BACKUP" - see below and section 7 |
| Exists, at 3.0.6CE or another version with no script | "A database from a previous version has been detected but it is not possible to upgrade the database automatically. NORD POS will exit now." - the application closes without changing anything. That dialog really does say **NORD POS**: the message bundles still carry the upstream product name (see section 7). |

The installer checks whether that directory exists and prints the matching version of these
steps at the end of its run. Read the dialog on screen - do not click Yes from memory.

### If this is a brand-new installation (no `.derby-db` yet)

1. **The dialog says a working database cannot be detected and a default one will be
   created. Click Yes.** This happens only the first time. It takes a second or two and
   writes about 5 MB into `~/.derby-db` (macOS) or `%USERPROFILE%\.derby-db` (Windows).
2. **The login screen appears with four buttons.** Click **Administrator**. There is no
   password yet - that is expected on a brand-new installation.
3. **Set passwords immediately.** Go to **Maintenance -> Users**. All four seeded accounts -
   Administrator, Manager, Employee and Guest - ship with no password at all. Anyone who can
   reach the till can log in as Administrator until you fix that. Do it before you sell
   anything.
4. **The catalogue starts empty.** A new database contains 0 products, 1 category
   ("Category Standard"), 2 taxes ("Tax Exempt" and "Tax Standard" at 10%) and one location
   called "General". Add your products and correct the tax rates before trading.
5. **Receipts go to an on-screen preview by default.** Nothing is printed until you set up a
   real printer under **Configuration -> Hardware**.

### If you already have a database from an older version

1. **Copy `.derby-db` somewhere safe before you launch the application at all.** Not after.
   The application closed is the right moment; copy the whole folder.

   ```sh
   cp -R ~/.derby-db ~/derby-db-backup-before-4.0          # macOS
   ```

   ```bat
   robocopy "%USERPROFILE%\.derby-db" "%USERPROFILE%\derby-db-backup-before-4.0" /E
   ```

2. **The dialog you get is the upgrade one, and it is not the same question.** It reads:
   *"A database from a previous version has been detected. The database will be upgraded
   automatically. DATA MAY BE LOST. FIRST CREATE A BACKUP. Do you want to continue?"*
   Clicking **Yes** runs the upgrade immediately and there is no undo - see section 7 for
   exactly what it does, including that it **deletes every parked ticket**. Clicking **No**
   closes the application and changes nothing, which is the safe answer until your backup is
   made.
3. After the upgrade, steps 2, 3 and 5 above still apply, except that your existing users,
   passwords, products and sales history are all still there - the catalogue is not empty and
   the accounts are your own.

---

## 6. Your data

Two things on your computer belong to you, not to the program:

| Path | What it is |
|---|---|
| `~/nordpos.properties` (macOS)<br>`%USERPROFILE%\nordpos.properties` (Windows) | Your settings: printers, customer display, scale, payment gateway, language, screen mode |
| `~/.derby-db` (macOS)<br>`%USERPROFILE%\.derby-db` (Windows) | **Your live sales database** - products, prices, customers, and every sale you have ever recorded |

**The installer and the uninstaller never delete, overwrite or move either of these.** The
only exception is the `--repair-laf` option described in section 8, which changes exactly one
line of the settings file and takes a timestamped backup first. The uninstallers can remove
your data, but only if you ask for it and then type a confirmation phrase by hand.

**Back up `.derby-db`.** It is an ordinary folder. Copy it somewhere safe on a schedule -
while the application is closed, so the database is not mid-write. Everything you have ever
sold lives there and nowhere else. Its location is fixed by the application itself (section
4): it is always `.derby-db` in your home folder, on both platforms and on every launch path,
and no flag or environment variable will move it.

On a brand-new installation the settings file does not exist until the first time you use
**Configuration -> Save** inside the application. That is normal and correct: with no file
present, BIZAPP POS uses its built-in defaults, and the launcher supplies the handful of
values that need to be right. It also means a rebuild or a moved folder cannot leave a stale
path behind. If you are installing over an existing setup the file is already there - back it
up along with `.derby-db`, and note that it is the one file `--repair-laf` may rewrite.

---

## 7. Database notes

**The default needs no setup.** BIZAPP POS starts its own Apache Derby database server when
it launches, listening on `127.0.0.1` port `1527`. That address is the machine itself, so the
database is never reachable from your network or the internet.

**Two tills, one database.** Two copies of BIZAPP POS on the same machine legitimately share
one Derby server and one database. If the port-check warning during install tells you that
something is already listening on 1527 and that something is another copy of BIZAPP POS or a
Derby server, this is fine.

**MySQL is supported, but you do the setup.** You must install MySQL yourself, create the
database and a user, and then in **Configuration -> Database** point `db.driverlib` at the
full path of a connector jar in the `lib-jdbc` folder. Mind the driver class name - it
changed between versions:

| Connector jar in `lib-jdbc/` | Driver class |
|---|---|
| `mysql-connector-java-5.1.34.jar` | `com.mysql.jdbc.Driver` |
| `mysql-connector-java-8.0.27.jar` | `com.mysql.cj.jdbc.Driver` |

**HSQLDB, PostgreSQL and FirebirdSQL do not work.** They appear in the database dropdown, but
this repository contains schema-creation scripts for Derby and MySQL only. Choosing one of the
other three gives you an application that cannot create its tables.

**Upgrading an existing old database: read this before you launch.** BIZAPP POS 4.0 does
**not** simply refuse to open an older database. When the version stamped inside the database
is not 4.0, the application looks for an upgrade script named after the version it found -
not after 4.0 - and this repository ships those scripts for Derby:

| Version stamped in your database | What BIZAPP POS 4.0 does |
|---|---|
| `nordpos` 2.30.2, 3.0.0CE, 3.0.1CE, 3.0.2CE, 3.0.3CE, 3.0.4CE, 3.0.5CE | Offers to **upgrade it in place** |
| `openbravopos` 2.30.2 (an original Openbravo POS database) | Offers to **upgrade it in place** |
| `nordpos` 3.0.6CE | Refuses and exits without changing anything - exact wording below |
| Anything else with no matching script | Refuses in the same way |

The refusal dialog reads, in full: *"A database from a previous version has been detected but
it is not possible to upgrade the database automatically. NORD POS will exit now."* (that is
the `message.noupdatescript` string in `locales/pos_messages.properties`, shown by
`JRootApp`). Two things about it are worth knowing before you meet it: it names **NORD POS**,
not BIZAPP POS, because the translated message bundles still carry the upstream product name -
it does not mean you installed the wrong application - and it is purely a refusal, so your
database is left exactly as it was. Restore or keep your backup and stay on the old version
until you have a migration path.

> **The upgrade is destructive and there is no undo.** The dialog it shows says so in its own
> words - *"DATA MAY BE LOST. FIRST CREATE A BACKUP."* Every one of the eight Derby upgrade
> scripts above contains `DELETE FROM SHAREDTICKETS`, so **all parked (suspended) customer
> tickets held at the till are discarded.** The scripts then stamp the database as version
> 4.0, which means the upgrade cannot be run again and cannot be reversed. If a statement in
> the script fails, the application collects it into a warning list and carries on rather than
> rolling back.

So, on any machine that already has a database:

1. **Copy `~/.derby-db` (macOS) or `%USERPROFILE%\.derby-db` (Windows) somewhere safe before
   the first launch of 4.0.** Close the old version first. That copy is your only way back.
2. Cash up and clear any parked tickets in the old version, because the upgrade will delete
   them.
3. Then launch, read the dialog, and answer Yes only when you are satisfied with the backup.
   Answering No closes the application without touching anything.

The same applies to MySQL, which additionally ships an upgrade script for 3.0.6CE - so on
MySQL every stored version from 2.30.2 upward upgrades in place, and all nine of those
scripts delete the parked tickets in the same way. Back up with `mysqldump` first.

This installer is written for new installations; upgrading is possible, as described above,
but test it on a copy of your database before you do it on the real till.

---

## 8. Look and feel - please read this one

BIZAPP POS is launched with the **Nimbus** look and feel. This is deliberate and it is not
cosmetic.

The application also lists a set of "Substance" skins in **Configuration -> General**. Those
skins crash on Java 9 and every version since - the crash happens before any window is drawn,
so you get no error, no window, and no obvious way back in.

> **Do not select a Substance skin from that dropdown.** If you do and then save, BIZAPP POS
> will not start again.

If it has already happened, the fix is one command. Re-run the installer with `--repair-laf`:

```sh
./install.command --repair-laf          # macOS
```

```bat
install.cmd --repair-laf
```

That changes only the `swing.defaultlaf` line in your settings file, after copying the whole
file to `nordpos.properties.bizapp-backup-<timestamp>` beside it. Every other setting is left
byte-for-byte as it was. If the installer notices a Substance skin in your settings but you
did not pass the flag, it warns you and changes nothing.

One more trap on the same settings screen: **do not clear the font-size field.** An empty
value there causes an error at startup.

---

## 9. Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| Installer says no JDK was found, but `java -version` works | You have a runtime, not a development kit. On Windows this is usually `C:\ProgramData\Oracle\Java\javapath`, which contains `java.exe` but no `javac.exe`. | Install a JDK (section 2). If you already have one, set `JAVA_HOME` to the folder that contains `bin\javac` (`bin/javac` on macOS) and try again. On Windows: `setx JAVA_HOME "C:\Program Files\Eclipse Adoptium\jdk-21"`, then open a **new** window. |
| Setup stops with exit code 3 and an error naming `JAVA_HOME`, on a machine that does have a working JDK | `JAVA_HOME` is set but points at a folder that has been deleted, moved or upgraded away, or at a JRE rather than a JDK. An explicit setting is always used or reported, never silently replaced by a different JDK (section 3), so a stale value stops the run instead of being ignored. | Either name the JDK for this run - `install.cmd --jdk "C:\Program Files\Eclipse Adoptium\jdk-21"` or `sh install.command --jdk /Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home` - or clear the stale variable and let setup search: `setx JAVA_HOME ""` and then open a **new** window (Windows), or `unset JAVA_HOME` (macOS). Check the value first with `echo %JAVA_HOME%` / `echo $JAVA_HOME`. |
| `./install.command: Permission denied` | You downloaded a ZIP from GitHub; ZIP files do not carry the executable permission. | Put `sh` in front of it: `sh install.command`. That works no matter which bits were lost, and the same trick applies to the uninstaller (`sh install/macos/uninstall.command`) and the build engine (`sh install/build.sh`). To get the double-click back as well, run `chmod +x install.command` once. |
| `bad interpreter: /bin/sh^M` | The shell scripts were checked out with Windows line endings, usually by a Git configured with `core.autocrlf=true`. | The repository ships a `.gitattributes` that prevents this. Re-clone, or run `git config core.autocrlf input` and then `git checkout -- .` in a clean checkout. |
| Nothing happens when you launch the app | The application failed before drawing a window. Everything it said went to the log. | Read `~/Library/Logs/BIZAPP POS/bizapp-pos.log` (macOS) or `%LOCALAPPDATA%\BIZAPP POS\logs\bizapp-0.log` (Windows). On Windows you can also run `install\windows\run.cmd` to see the errors live in a console. |
| A window flashes and vanishes, or the log shows a `NullPointerException` mentioning `pushingpixels` | A Substance skin was saved into your settings (section 8). | Re-run the installer with `--repair-laf`. |
| The app hangs at startup with no window at all | Something that is not Derby is already listening on TCP port 1527. | Find it: `lsof -nP -iTCP:1527 -sTCP:LISTEN` (macOS) or `Get-NetTCPConnection -State Listen -LocalPort 1527` (Windows). If it is another BIZAPP POS or a Derby server, that is fine. Anything else must be stopped. |
| A dialog says the database driver was not found | The checkout was moved, renamed or deleted after installing. | Run the installer again from the folder's current location. |
| Windows says the path is too long, or the build fails with strange missing-file errors | Windows limits most paths to 260 characters and BIZAPP POS needs about 120 of them for its own files. | Move the folder somewhere short, such as `C:\BizappPOS`, and run `install.cmd` again. |
| PowerShell refuses to run the script ("running scripts is disabled on this system") | Execution policy, or the files came from a downloaded ZIP and are marked as web content. | Use `install.cmd`, not the `.ps1` files - it sets the policy for its own run only. If it still fails, unblock the files: `Get-ChildItem -Recurse .\install \| Unblock-File`. On a managed company machine a group policy can override this; ask your IT administrator. |
| macOS says the app "is damaged" or is "from an unidentified developer" | Gatekeeper. This should not happen for an app you built yourself, only for one someone sent you. | Right-click the app -> **Open** -> **Open**, or **System Settings -> Privacy & Security -> Open Anyway**. As a last resort: `xattr -dr com.apple.quarantine "$HOME/Applications/BIZAPP POS.app"`. Never disable Gatekeeper globally. |
| Windows SmartScreen shows a blue "Windows protected your PC" banner | The files carry the mark-of-the-web, which happens when you download a ZIP rather than using `git clone`. | Click **More info -> Run anyway**, or clear the mark first with `Get-ChildItem -Recurse .\install \| Unblock-File`. |
| A firewall prompt appears naming "Java(TM) Platform SE binary" | BIZAPP POS checks whether another copy is already running, using a local RMI registry on port 1099. | **Cancel is safe.** The database itself listens on 127.0.0.1 only and needs no rule. The installer never changes firewall settings. |
| Your folder path contains spaces, accented characters, an apostrophe or `#`, and something fails | It should not. The build engine quotes every token it hands to the compiler unless the token is drawn from a small unambiguously safe set, so a path like `C:\Users\O'Brien\pointofsale` or `/Users/me/pos #1` is passed through intact. | Verified on macOS for spaces, accented characters, `'` and `#`, both in the checkout path and in a `--build-dir` / `--dist-dir` outside the repository. The Windows port applies the identical rule but has not been run on Windows hardware (section 13). If it still fails, please report the path. |
| Login screen appears but no password works | New installations have **no** passwords. | Click **Administrator** with the password field empty, then set passwords under **Maintenance -> Users**. |
| A serial (RS-232) scale or receipt printer does not work on an Apple Silicon Mac | The bundled serial library ships Intel-only Mac binaries. There is no arm64 build. | Use a USB or network device instead. Serial devices are unaffected on Intel Macs and on Windows. |
| Antivirus quarantines `libNRJavaSerial.dll` on Windows | The serial library unpacks a driver into your temp folder at runtime, which some scanners flag. | Ask your IT administrator for an exclusion for that file. Do not disable Windows Defender. |

If none of these match, the log file is the place to look first, and the exit code from the
installer (section 11) tells you which stage failed.

---

## 10. Uninstall

```sh
sh install/macos/uninstall.command     # macOS
```

```bat
install\windows\uninstall.cmd
```

On macOS `./install/macos/uninstall.command` works too when the executable bit survived; `sh`
in front of it works either way, which matters after a ZIP download.

Both show you exactly what they will remove and what they will keep, and then ask you to
confirm. Both **refuse to run while BIZAPP POS is open**, because the database is locked while
the application holds it - closing it first protects your data.

**Removed:** the macOS `~/Applications/BIZAPP POS.app` (and `/Applications/BIZAPP POS.app` if
you used `--system`), the Windows Start Menu and Desktop shortcuts, the log folder, and the
`build/` and `dist/` folders inside the checkout. Pass `--keep-build` to leave the built
application in place.

**If you installed with `--dest <dir>`** (or with `BIZAPP_APP_DEST` set), give the macOS
uninstaller the **same** value - it does not remember it, and without it the bundle in your
own directory is left behind:

```sh
sh install/macos/uninstall.command --dest "$HOME/bizapp-test"
```

`--dest` there is additive: `~/Applications` and `/Applications` are still checked as well.

**Kept:** `nordpos.properties` and `.derby-db` - your settings and your sales database.

**Removing your data as well** is possible but deliberately awkward: pass `--purge-data`
(macOS) or `--remove-data` (Windows), and you will then have to type `DELETE MY DATA` exactly,
capital letters and all. `--yes` does not skip that prompt and nothing else can.

Neither uninstaller deletes the source folder. Delete it yourself when you are finished with
it.

---

## 11. Building from source (for developers)

Both installers are thin wrappers around one shared build engine, which you can run directly.
**NetBeans and Apache Ant are not required and are not used.**

```sh
sh install/build.sh
```

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File install\build.ps1
```

The two ports have identical command-line interfaces, identical stdout, and identical exit
codes.

### Flags

| Flag | Meaning |
|---|---|
| `--build-dir <path>` | Intermediate output. Default `<repo>/build`. |
| `--dist-dir <path>` | Final output. Default `<repo>/dist`. |
| `--jdk <java_home>` | Use this JDK. Beats `BIZAPP_JDK_HOME`, `JAVA_HOME` and auto-discovery. |
| `--release <n>` | `javac --release` level. Default 11. |
| `--clean` | Delete this engine's own output first - `classes/`, `javac.args`, `MANIFEST.MF`, `classpath.txt`, `build-info.txt`, `nordpos.jar` - then remove the build and dist directories themselves if they are left empty. Other files are never touched. |
| `--quiet` | Suppress progress lines. Warnings and errors are never suppressed. |
| `--verify` | Print extra detail from the self-check (which always runs anyway). |
| `--print-classpath` | Print the resolved classpath on one line and exit. Builds nothing. |
| `--print-jar` | Print the path of the jar that would be produced and exit. |
| `--version`, `--help` | Version banner / usage. |

Every flag accepts both `--name value` and `--name=value`.

### Environment variables

| Variable | Read by | Meaning |
|---|---|---|
| `BIZAPP_BUILD_DIR` | build engine | Fallback for `--build-dir`. The flag wins. |
| `BIZAPP_DIST_DIR` | build engine | Fallback for `--dist-dir`. The flag wins. |
| `BIZAPP_JDK_HOME` | build engine, installers | Selects a JDK. `--jdk` wins over it, and it wins over `JAVA_HOME` - see "Choosing which JDK is used" in section 3. |
| `JAVA_HOME` | build engine, installers | Used when neither `--jdk` nor `BIZAPP_JDK_HOME` is set. |
| `BIZAPP_APP_DEST` | macOS installer and uninstaller | Fallback for `--dest`. The flag wins. macOS only. |
| `BIZAPP_JAVA_HOME` | launchers | Run the application on this JVM, overriding the one recorded at install time. Read by the macOS `.app` and by `install\windows\run.cmd`; not read by the build and not read by the Windows shortcuts. |
| `BIZAPP_JAVA_OPTS` | some launchers - see below | Extra JVM options at run time, not at build time. |

**`BIZAPP_JAVA_OPTS` is not honoured everywhere, so check this before you rely on it.** It is
read at launch, by the process that starts the JVM, and only these read it:

| Launch path | Reads `BIZAPP_JAVA_OPTS`? |
|---|---|
| macOS `BIZAPP POS.app` | Yes |
| Windows `install\windows\run.cmd` | Yes |
| Windows `install.cmd --launch` | Yes |
| Windows Start Menu / Desktop shortcut | **No** |

A Windows `.lnk` shortcut cannot consult an environment variable at all: its JVM options are
baked in when the shortcut is created, and `setx BIZAPP_JAVA_OPTS ...` will have no effect on
it, silently. If you need a different heap ceiling from the shortcut - the built-in value is
`-Xmx1024m` - you have three choices: launch from `install\windows\run.cmd` instead, edit
the shortcut (right-click -> **Properties** -> **Target**) and change `-Xmx1024m` there by
hand, or re-run `install.cmd` after editing the value in
`install\windows\lib\Install-BizappPos.ps1`. On macOS the `.app` reads the variable from
the environment it is launched in, which for a Finder or Dock launch is the login
environment, not the one in your Terminal session; `launchctl setenv BIZAPP_JAVA_OPTS
"-Xmx3072m"` and then relaunching is the way to make it apply there.

### Output contract

All human-readable output - the banner, `==>` progress lines, warnings, errors and the output
of `javac` and `jar` - goes to **stderr**. **stdout** carries only two machine-readable lines,
so you can safely capture it:

```sh
OUT=$(sh install/build.sh --quiet)
JAR=$(printf '%s\n' "$OUT" | sed -n 's/^BIZAPP_JAR=//p')
```

```
BIZAPP_JAR=<absolute path to dist/nordpos.jar>
BIZAPP_CLASSPATH_FILE=<absolute path to dist/classpath.txt>
```

A successful build also writes `dist/classpath.txt` (the library list, one per line) and
`dist/build-info.txt` (version, build time, JDK used, file counts, jar size, git revision).

### Exit codes

Shared by the build engine, the installers, the uninstallers and the launchers.

| Code | Meaning |
|---|---|
| 0 | Success |
| 1 | BIZAPP POS is already running (launcher and uninstaller only) |
| 2 | Bad command-line usage, or a required built file is missing |
| 3 | No suitable JDK or JRE found |
| 4 | A JDK was found but it is older than 11 |
| 5 | The folder does not look like a BIZAPP POS checkout - a file or directory is missing |
| 6 | Compilation failed |
| 7 | Packaging failed |
| 8 | The built jar failed its self-check |
| 9 | You declined a consent prompt, or a prompt was needed and there was no terminal to ask in |
| 10 | Environment problem - wrong operating system, path too long, unusable characters in the path |
| 9009 | Windows only: `powershell.exe` was not found |

### Running in parallel

Give each build its own output directories:

```sh
sh install/build.sh --build-dir build-v1 --dist-dir build-v1/dist
```

Use a `build`-prefixed name. Do **not** invent a `dist`-prefixed one - the repository contains
a tracked directory literally named `dist - bizpapp`, and a `dist*` pattern would collide with
it.

### Notes on what the build does

- **One line of `nbproject/project.properties` was fixed.** The reference to the Substance jar
  pointed at `C:\code\pos\substance-7.2.1.jar`, a path that exists on nobody's machine. It now
  points at `lib/substance-7.2.1.jar`. That makes the existing NetBeans project strictly
  better; nothing else in the NetBeans configuration was touched, and the project still opens
  and builds there as before.
- **The classpath is read from `javac.classpath` in that same file**, not globbed from `lib/`.
  Globbing would additionally pick up a second, incompatible copy of the Substance library,
  and would order the jars differently on Windows and macOS.
- **The jar deliberately includes three things the old Ant build and the committed
  `dist - bizpapp/nordpos.jar` both omit:** `services/META-INF/services` (without it every
  receipt printer, customer display, scale, label printer and fiscal printer driver silently
  does nothing, and card payment throws an exception), `templates/` (a required XML schema),
  and `transformations/` (the CSV import/export definitions).
- **Do not use `dist - bizpapp/nordpos.jar`.** It is a stale prebuilt artifact kept only for
  reference, and it is missing all of the above. `dist.rar` is the same content in an archive.
  Build your own.
- **Icons are generated from the artwork in the repository.** The macOS `.icns` comes from
  `src-pos/com/openbravo/pos/templates/Window.DescLogo.png` (600x300) and the Windows `.ico`
  from `src-beans/com/openbravo/images/favicon.png` (40x40). Both are upscaled at the largest
  sizes and look soft in Finder's and Explorer's biggest icon views. Dropping in a 1024x1024
  master and regenerating fixes that with no change to any script.

---

## 12. Security notes

**Nothing is downloaded and nothing is signed.** The application is compiled on your own
machine from the source you cloned. There is no installer binary to trust, and equally there
is no code signature to check - the security boundary is the source repository itself.

**Neither installer elevates by default.** In a normal run neither uses `sudo`, requests
administrator approval, modifies `PATH` or system environment variables, touches antivirus or
Gatekeeper settings, or **adds a firewall rule**. Three actions can involve administrator
rights. Each one prints what it is about to do, defaults to "no", and never happens on its
own:

| Action | Platform | What it does with administrator rights |
|---|---|---|
| `--system` | macOS | **The installer itself runs `sudo`**, to put `BIZAPP POS.app` into `/Applications`. It first prints the exact list of `sudo` commands it will run - a `mkdir -p`, two or three `mv`s of its own staged copies, and the matching rollback commands - and nothing else runs as root. |
| The Homebrew JDK offer | macOS | Made only when no JDK was found and `brew` is on `PATH`: `brew install --cask temurin@21`. The installer does not call `sudo` here; Homebrew asks for the password itself, because a cask installs system-wide. |
| `--install-jdk` | Windows | Installs Eclipse Temurin 17 through winget or Chocolatey, which can raise a UAC prompt. Without the flag, an interactive run offers the same thing at a prompt. |

`--yes` pre-answers all three: it accepts the two JDK offers outright, and it skips the
`--system` confirmation - though macOS itself will still ask you for the password when `sudo`
runs. Without `--yes`, declining any of them exits with code 9 and changes nothing. In a
non-interactive session with no `--yes` there is nobody to ask, so they are refused rather
than assumed.

**macOS.** An application you build yourself is not quarantined, so `~/Applications/BIZAPP
POS.app` simply opens. Gatekeeper only objects if someone *sends* you the built `.app` or a
disk image. In that case: right-click -> **Open** -> **Open**, or **System Settings -> Privacy
& Security -> Open Anyway**, or as a last resort
`xattr -dr com.apple.quarantine "$HOME/Applications/BIZAPP POS.app"`. Never run
`spctl --master-disable` - it turns off Gatekeeper for everything on the machine, forever.
Proper notarization would need a paid Apple Developer ID and is out of scope for this project.

**Windows.** `git clone` does not mark files as web content, so the scripts run without a
SmartScreen prompt. Files extracted from a downloaded ZIP *are* marked; clear it with
`Get-ChildItem -Recurse .\install | Unblock-File`.

**The firewall prompt.** If Windows asks about "Java(TM) Platform SE binary", it is the
single-instance check binding a local RMI registry on port 1099. Cancelling is safe and
correct. The database listens on `127.0.0.1:1527` only and is not reachable from your network.

**Passwords.** A fresh database has four accounts with no passwords (section 5). Until you set
them, anyone with access to the machine has full administrator rights over your till.

---

## 13. Known limitations

Stated plainly, so you are not surprised.

- **The Windows path has not been tested on real Windows hardware.** No Windows machine was
  available while this installer was written. The compiler and packaging steps are
  platform-independent and are verified on macOS with Java 11 and 24, but the batch-file
  parsing, the registry-based JDK discovery and the Start Menu / Desktop shortcut creation are
  reasoned rather than run. If something misbehaves there, please report it - and
  `install\windows\run.cmd` will start the application regardless of whether the shortcuts are
  correct.
- **Java 25 and newer may not compile at all.** One source file still imports `java.applet`,
  which is being removed from Java.
- **The Substance look and feel is worked around, not fixed** (section 8). Selecting one of
  those skins in the application will still break your own startup until you run
  `--repair-laf`.
- **Upgrading an older database is destructive and cannot be undone** (section 7). Databases
  stamped 2.30.2 or 3.0.0CE through 3.0.5CE are upgraded in place and lose every parked
  ticket; only 3.0.6CE is refused outright. Back up `.derby-db` before the first launch.
- **The database location cannot be changed** (section 4). It is always `.derby-db` in your
  home folder; the application overrides any attempt to set it elsewhere.
- **`BIZAPP_JAVA_OPTS` does not reach the Windows Start Menu and Desktop shortcuts**
  (section 11). A `.lnk` cannot read an environment variable; edit the shortcut or use
  `install\windows\run.cmd`.
- **The launcher points at the checkout**, so moving the folder breaks it until you re-run the
  installer (section 4).
- **Serial peripherals do not work on Apple Silicon Macs** (section 9).
- **The first run asks one question** - whether to create the database, or on a machine that
  already has one, whether to upgrade it - and nothing in the installer can pre-answer it.
  "One command to a running till" means one command to a window that then asks you a single
  Yes/No question. Read which of the two questions it is (section 5) before answering.

---

BIZAPP POS is free software under the GNU General Public License, version 3 or later. See
[LICENSE](LICENSE).
