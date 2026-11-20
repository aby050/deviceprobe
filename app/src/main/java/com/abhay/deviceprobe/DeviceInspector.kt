package com.abhay.deviceprobe

import java.util.Locale

/**
 * Runs the checks on a connected Android device.
 * [sh] runs one shell command on that device and returns its output.
 */
class DeviceInspector(
    private val sh: (String) -> String,
    private val progress: (String) -> Unit,
) {
    private var props: Map<String, String> = emptyMap()
    private var features: Set<String> = emptySet()
    private var packages: String = ""

    private fun p(vararg keys: String): String {
        for (k in keys) {
            val v = props[k]
            if (!v.isNullOrBlank()) return v.trim()
        }
        return ""
    }

    private fun run(cmd: String): String = try { sh(cmd).trim() } catch (e: Exception) { "" }

    fun inspect(): Report {
        progress("Reading device identity")
        props = Regex("""\[(.+?)]: \[(.*?)]""").findAll(run("getprop"))
            .associate { it.groupValues[1] to it.groupValues[2] }
        if (props.isEmpty()) throw IllegalStateException("The device did not answer. Unlock it and try again.")
        features = run("pm list features").lines().map { it.removePrefix("feature:").substringBefore('=').trim() }.toSet()
        packages = run("pm list packages")

        val identity = identity()
        progress("Checking locks and security")
        val security = security()
        progress("Checking battery")
        val battery = battery()
        progress("Checking screen and parts")
        val parts = parts()
        progress("Building report")

        val name = listOf(p("ro.product.manufacturer").replaceFirstChar { it.uppercase() }, marketName())
            .filter { it.isNotBlank() }.joinToString(" ")
        return Report(
            deviceName = name.ifBlank { "Android device" },
            overview = overview(battery),
            sections = buyerSections(identity + security + battery.items + parts),
            details = listOf(
                Section("Authenticity", identity),
                Section("Locks and security", security),
                Section("Battery", battery.items),
                Section("Screen and parts", parts),
            ),
        )
    }

    /**
     * The report a buyer reads: only what decides whether the phone is genuine, unlocked and
     * has original working parts. A group of checks that all pass becomes one green line;
     * anything that did not pass is always shown in full.
     */
    private fun buyerSections(all: List<Item>): List<Section> {
        fun bad(i: Item) = i.status == Status.WARN || i.status == Status.FAIL
        fun pick(vararg labels: String) = labels.flatMap { l -> all.filter { it.label == l } }
        fun merge(label: String, ok: String, note: String, vararg labels: String): List<Item> {
            val found = pick(*labels)
            return when {
                found.any(::bad) -> found.filter(::bad)
                found.none { it.status == Status.PASS } -> emptyList()
                else -> listOf(Item(label, ok, Status.PASS, note))
            }
        }
        val model = listOf(p("ro.product.manufacturer").replaceFirstChar { it.uppercase() }, p("ro.product.model"))
            .filter { it.isNotBlank() }.joinToString(" ")
        return listOf(
            Section(
                "Is it a genuine phone?",
                merge(
                    "Genuine device", "Yes, a real $model",
                    "Its chip, firmware and software signature all match this model.",
                    "Device type", "Firmware identity", "Android version", "Chipset", "Software signature", "Build type",
                ) + merge(
                    "Software", "Original, never modified",
                    "Not rooted and the bootloader is locked.",
                    "Bootloader", "Samsung Knox", "Root", "System protection (SELinux)",
                ),
            ),
            Section("Locks that can block you", pick("Google account lock (FRP)", "Company management (MDM)")),
            Section(
                "Battery",
                pick("Battery life", "Charge cycles", "Battery replacement", "Battery date") +
                    pick("Battery condition", "Temperature").filter(::bad),
            ),
            Section(
                "Screen and parts",
                pick("Screen part", "Screen panel ID", "Cameras") +
                    merge(
                        "Sensors", "All working", "Motion, gyroscope, proximity, light and compass all respond.",
                        "Motion sensor", "Gyroscope", "Proximity sensor", "Light sensor", "Compass",
                    ) +
                    pick("Fingerprint reader", "Mobile radio") +
                    pick("Storage wear", "System crashes", "Screen resolution").filter(::bad),
            ),
        ).filter { it.items.isNotEmpty() }
    }

    // ---------------------------------------------------------------- identity

    private fun marketName(): String = p(
        "ro.product.marketname", "ro.config.marketing_name", "ro.product.vendor.marketname",
        "ro.product.odm.marketname", "ro.vendor.oplus.market.name", "ro.product.model",
    )

    private fun socName(): String {
        val soc = listOf(p("ro.soc.manufacturer"), p("ro.soc.model")).filter { it.isNotBlank() }.joinToString(" ")
        return soc.ifBlank { p("ro.board.platform", "ro.hardware") }
    }

    private fun isEmulator(): Boolean =
        p("ro.kernel.qemu", "ro.boot.qemu") == "1" || p("ro.hardware") in setOf("ranchu", "goldfish")

    private fun imei(): String {
        for (code in listOf(1, 4)) {
            val out = run("service call iphonesubinfo $code s16 com.android.shell")
            if (!out.contains("Parcel")) continue
            val digits = Regex("'(.*?)'").findAll(out).joinToString("") { it.groupValues[1] }
                .filter { it.isDigit() }
            if (digits.length in 14..16) return digits
        }
        return ""
    }

    private fun identity(): List<Item> {
        val items = mutableListOf<Item>()
        val brand = p("ro.product.brand")
        val model = p("ro.product.model")
        val device = p("ro.product.device")
        val fingerprint = p("ro.build.fingerprint")
        val platform = (p("ro.board.platform") + " " + p("ro.hardware") + " " + p("ro.soc.manufacturer")).lowercase(Locale.US)

        if (isEmulator()) {
            items += Item("Device type", "Emulator", Status.WARN, "This is a virtual device, not a real phone.")
        }

        // 1. Does the firmware's identity match what the phone claims to be?
        if (fingerprint.isNotBlank()) {
            val fpBrand = fingerprint.substringBefore('/')
            val fpDevice = fingerprint.substringAfter('/').substringAfter('/').substringBefore(':')
            val brandOk = fpBrand.equals(brand, true) || fpBrand.equals(p("ro.product.system.brand"), true) ||
                fpBrand.equals(p("ro.product.vendor.brand"), true)
            val deviceOk = fpDevice.equals(device, true) || fpDevice.equals(p("ro.product.vendor.device"), true) ||
                fpDevice.equals(p("ro.product.system.device"), true)
            items += if (brandOk && deviceOk) {
                Item("Firmware identity", "Matches the device", Status.PASS)
            } else {
                Item(
                    "Firmware identity", "Does not match ($fpBrand/$fpDevice vs $brand/$device)", Status.WARN,
                    "The software says it was built for a different device. Seen on clones and on phones with modified firmware.",
                )
            }
        }

        // 2. Is the Android version it shows the one it really runs?
        val sdk = p("ro.build.version.sdk").toIntOrNull()
        val release = p("ro.build.version.release")
        val expected = SDK_TO_RELEASE[sdk]
        if (sdk != null && expected != null) {
            val shown = release.substringBefore('.').toIntOrNull()
            items += if (shown == expected) {
                Item("Android version", "Android $release is real", Status.PASS)
            } else {
                Item(
                    "Android version", "Shows Android $release, really runs Android $expected", Status.FAIL,
                    "The version shown in Settings is faked. This is a strong sign of a clone.",
                )
            }
        }

        // 3. Does the chip inside fit the model on the label?
        val soc = socName()
        val mediatek = platform.contains("mt6") || platform.contains("mt8") || platform.contains("mediatek")
        val claimsApple = (brand + model + marketName()).lowercase(Locale.US).let { it.contains("iphone") || it.contains("apple") }
        val samsungFlagship = brand.equals("samsung", true) && Regex("""SM-(S9|G9[6-9]|N9|F9|F7)""").containsMatchIn(model)
        val pixel = brand.equals("google", true) && model.startsWith("Pixel", true)
        items += when {
            claimsApple -> Item("Chipset", soc, Status.FAIL, "This is an Android device made to look like an iPhone.")
            (samsungFlagship || pixel) && mediatek -> Item(
                "Chipset", soc, Status.FAIL,
                "$model was never made with a MediaTek chip. This is a clone.",
            )
            soc.isBlank() -> Item("Chipset", "Not reported", Status.INFO)
            else -> Item("Chipset", "$soc fits this model", Status.PASS)
        }

        // 4. Official software?
        val tags = p("ro.build.tags")
        items += when {
            tags.contains("release-keys") -> Item("Software signature", "Official (release-keys)", Status.PASS)
            tags.isBlank() -> Item("Software signature", "Not reported", Status.INFO)
            else -> Item(
                "Software signature", tags, Status.WARN,
                "Not signed with the maker's official keys: custom, leaked or clone firmware.",
            )
        }
        val type = p("ro.build.type")
        if (type.isNotBlank() && type != "user") {
            items += Item("Build type", type, Status.WARN, "A test or engineering build, not the retail software.")
        }

        // 5. Mobile radio
        if ("android.hardware.telephony" in features) {
            val baseband = p("gsm.version.baseband")
            items += if (baseband.isBlank() || baseband.equals("unknown", true)) {
                Item(
                    "Mobile radio", "No baseband found", Status.FAIL,
                    "The phone cannot see its modem. Calls and mobile data will not work, and the IMEI may be missing.",
                )
            } else {
                Item("Mobile radio", "Working", Status.PASS)
            }
        }
        return items
    }

    // ---------------------------------------------------------------- security

    private fun security(): List<Item> {
        val items = mutableListOf<Item>()

        val vb = p("ro.boot.verifiedbootstate")
        val locked = p("ro.boot.flash.locked")
        val vbmeta = p("ro.boot.vbmeta.device_state")
        val unlocked = vb == "orange" || locked == "0" || vbmeta == "unlocked"
        items += when {
            unlocked -> Item(
                "Bootloader", "Unlocked", Status.FAIL,
                "The phone's software protection is off. It may run modified software, and banking apps can refuse to work.",
            )
            vb == "yellow" -> Item("Bootloader", "Locked, custom key", Status.WARN, "Runs software that is not from the maker.")
            vb == "green" || locked == "1" || vbmeta == "locked" -> Item("Bootloader", "Locked, software verified", Status.PASS)
            else -> Item("Bootloader", "Not reported", Status.INFO)
        }

        if (p("ro.product.brand").equals("samsung", true)) {
            val knox = p("ro.boot.warranty_bit", "ro.warranty_bit")
            if (knox.isNotBlank()) {
                items += if (knox == "0") {
                    Item("Samsung Knox", "Intact (0x0)", Status.PASS)
                } else {
                    Item(
                        "Samsung Knox", "Tripped (0x1)", Status.FAIL,
                        "This phone was rooted or flashed with unofficial software at some point. Samsung Pay and Secure Folder are disabled for good.",
                    )
                }
            }
        }

        val suPath = run("which su 2>/dev/null; ls /system/xbin/su /system/bin/su /sbin/su /debug_ramdisk/su 2>/dev/null")
        val rootApps = ROOT_PACKAGES.filter { packages.contains("package:$it") }
        val shellIsRoot = run("id").contains("uid=0")
        items += if (suPath.contains("su") || rootApps.isNotEmpty() || shellIsRoot) {
            Item(
                "Root", "Rooted", Status.FAIL,
                (if (rootApps.isNotEmpty()) "Found: ${rootApps.joinToString()}. " else "") +
                    "Root gives apps full control of the phone and voids the warranty.",
            )
        } else {
            Item("Root", "Not rooted", Status.PASS)
        }

        val selinux = run("getenforce")
        if (selinux.isNotBlank()) {
            items += if (selinux.equals("Enforcing", true)) Item("System protection (SELinux)", "On", Status.PASS)
            else Item("System protection (SELinux)", selinux, Status.WARN, "Android's app isolation is switched off.")
        }

        val accounts = run("dumpsys account")
        // Only real signed-in accounts ("Account {name=..., type=com.google}"), not the
        // sign-in services every phone lists as "AuthenticatorDescription {type=com.google}".
        val googleNames = Regex("""Account \{name=([^,}]+), type=com\.google\}""").findAll(accounts).map { it.groupValues[1] }.toSet()
        val google = googleNames.size
        items += if (google > 0) {
            Item(
                "Google account lock (FRP)", "$google Google account${if (google > 1) "s" else ""} signed in", Status.WARN,
                "If the phone is reset now it will ask for this account's password. Have the seller remove the account in Settings first.",
            )
        } else if (accounts.contains("Accounts:")) {
            Item("Google account lock (FRP)", "No Google account on the phone", Status.PASS)
        } else {
            Item("Google account lock (FRP)", "Could not read", Status.INFO)
        }

        val policy = run("dumpsys device_policy")
        items += when {
            policy.contains("Device Owner", true) && !policy.contains("Device Owner: null", true) &&
                Regex("""Device Owner[^\n]*\n[^\n]*(admin=|package=)""").containsMatchIn(policy) -> Item(
                "Company management (MDM)", "Managed by an organisation", Status.FAIL,
                "The phone is controlled remotely by a company or school. They can lock or wipe it at any time.",
            )
            policy.isBlank() -> Item("Company management (MDM)", "Could not read", Status.INFO)
            else -> Item("Company management (MDM)", "Not managed", Status.PASS)
        }

        val crypto = p("ro.crypto.state")
        if (crypto.isNotBlank()) {
            items += if (crypto == "encrypted") Item("Storage encryption", "On", Status.PASS)
            else Item("Storage encryption", crypto, Status.WARN, "Data on the phone is not encrypted.")
        }
        return items
    }

    // ---------------------------------------------------------------- battery

    class BatteryResult(val items: List<Item>, val healthPct: Int?, val cycles: Int?, val estimated: Boolean)

    private fun battery(): BatteryResult {
        val items = mutableListOf<Item>()
        val dump = run("dumpsys battery")
        val kv = dump.lines().mapNotNull {
            val i = it.indexOf(':')
            if (i <= 0) null
            else it.substring(0, i).trim().lowercase(Locale.US) to it.substring(i + 1).trim().removeSurrounding("[", "]").trim()
        }.toMap()
        val sys = run(
            "for d in battery bms; do for f in cycle_count charge_full charge_full_design fg_asoc battery_cycle; do " +
                "echo \"\$f=\$(cat /sys/class/power_supply/\$d/\$f 2>/dev/null)\"; done; done",
        ).lines().mapNotNull {
            val i = it.indexOf('=')
            if (i <= 0 || i == it.length - 1) null else it.substring(0, i) to it.substring(i + 1).trim()
        }.toMap()
        val stats = run("dumpsys batterystats | grep -i 'capacity' | head -12")
        fun mah(label: String): Int? =
            Regex("""$label[^:\n]*:\s*(\d+)""", RegexOption.IGNORE_CASE).find(stats)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it > 0 }

        val level = kv["level"]?.toIntOrNull()
        val design = mah("Estimated battery capacity") ?: Regex("""Capacity:\s*(\d+)""").find(stats)?.groupValues?.get(1)?.toIntOrNull()
        val learned = mah("Last learned battery capacity")
        val chargeCounter = kv["charge counter"]?.toLongOrNull()

        // Health %, best source first.
        var estimated = false
        // Samsung keeps two health figures; report the lower one.
        val asoc = listOfNotNull(
            (kv["msavedbatteryasoc"] ?: sys["fg_asoc"])?.toIntOrNull(),
            kv["msavedbatterybsoh"]?.toIntOrNull(),
        ).filter { it in 1..100 }.minOrNull()
        val full = sys["charge_full"]?.toLongOrNull()
        val fullDesign = sys["charge_full_design"]?.toLongOrNull()
        var health: Int? = when {
            asoc != null -> asoc
            full != null && fullDesign != null && fullDesign > 0 -> (full * 100 / fullDesign).toInt()
            learned != null && design != null && design > 0 -> learned * 100 / design
            else -> null
        }
        if (health == null && chargeCounter != null && level != null && level >= 30 && design != null && design > 0) {
            val nowMah = if (chargeCounter > 100_000) chargeCounter / 1000 else chargeCounter
            health = (nowMah * 100 / level * 100 / design).toInt()
            estimated = true
        }
        health = health?.takeIf { it in 20..130 }?.coerceAtMost(100)

        items += if (health == null) {
            Item("Battery life", "Not reported by this phone", Status.INFO, "Check Settings > Battery on the phone itself.")
        } else {
            val text = "$health%" + if (estimated) " (estimate)" else ""
            when {
                health >= 85 -> Item("Battery life", text, Status.PASS, "Share of the original capacity the battery still holds.")
                health >= 75 -> Item("Battery life", text, Status.WARN, "Noticeably worn. It will need replacing before long.")
                else -> Item("Battery life", text, Status.FAIL, "Badly worn. Plan to replace the battery.")
            }
        }

        val cycles = (kv.entries.firstOrNull { it.key.contains("cycle") }?.value?.toIntOrNull()
            ?: sys["cycle_count"]?.toIntOrNull()
            ?: sys["battery_cycle"]?.toIntOrNull()
            ?: kv["msavedbatteryusage"]?.toIntOrNull()?.let { it / 100 })?.takeIf { it >= 0 }
        items += when {
            cycles == null -> Item("Charge cycles", "Not reported by this phone", Status.INFO)
            cycles > 800 -> Item("Charge cycles", "$cycles", Status.WARN, "Heavy use. Most batteries are rated for 500 to 800 cycles.")
            else -> Item("Charge cycles", "$cycles", Status.PASS)
        }

        val healthCode = kv["health"]?.toIntOrNull()
        if (healthCode != null) {
            val (text, status) = when (healthCode) {
                2 -> "Good" to Status.PASS
                3 -> "Overheating" to Status.FAIL
                4 -> "Dead" to Status.FAIL
                5 -> "Over voltage" to Status.FAIL
                6 -> "Failure reported" to Status.FAIL
                7 -> "Too cold" to Status.WARN
                else -> "Unknown" to Status.INFO
            }
            items += Item("Battery condition", text, status)
        }
        kv["temperature"]?.toIntOrNull()?.let { t ->
            val c = t / 10.0
            items += Item("Temperature", "$c °C", if (c > 45) Status.WARN else Status.PASS, if (c > 45) "Hot for a phone at rest." else null)
        }
        kv["voltage"]?.toIntOrNull()?.let { v ->
            val volts = if (v > 100_000) v / 1_000_000.0 else v / 1000.0
            items += Item("Voltage", String.format(Locale.US, "%.2f V", volts), Status.INFO)
        }
        if (design != null) items += Item("Design capacity", "$design mAh", Status.INFO)
        if (level != null) items += Item("Charge now", "$level%", Status.INFO)

        // Samsung stores the battery's own dates. Compared with the phone's production date they show a swap.
        fun ymd(v: String?): String? = v?.filter { it.isDigit() }?.takeIf { it.length == 8 }
        fun pretty(d: String) = "${d.substring(6, 8)}/${d.substring(4, 6)}/${d.substring(0, 4)}"
        val batteryDate = ymd(kv["llb cal"]) ?: ymd(kv["llb man"])
        val firstUse = ymd(kv["battery firstusedate"])
        val phoneMade = ymd(p("ril.rfcal_date", "ro.boot.rf_cal_date"))
        if (batteryDate != null && phoneMade != null) {
            val months = (batteryDate.substring(0, 4).toInt() - phoneMade.substring(0, 4).toInt()) * 12 +
                (batteryDate.substring(4, 6).toInt() - phoneMade.substring(4, 6).toInt())
            items += if (months <= 2) {
                Item("Battery replacement", "Original battery", Status.PASS, "Battery date ${pretty(batteryDate)} matches the phone's production date ${pretty(phoneMade)}.")
            } else {
                Item(
                    "Battery replacement", "Battery probably replaced", Status.WARN,
                    "Battery date ${pretty(batteryDate)} is $months months newer than the phone (${pretty(phoneMade)}).",
                )
            }
        } else if (batteryDate != null) {
            items += Item("Battery date", pretty(batteryDate), Status.INFO, "Date stored in the battery. Much newer than the phone means the battery was replaced.")
        }
        if (firstUse != null) items += Item("Battery first used", pretty(firstUse), Status.INFO)
        return BatteryResult(items, health, cycles, estimated)
    }

    // ---------------------------------------------------------------- parts

    private fun parts(): List<Item> {
        val items = mutableListOf<Item>()

        // Screen
        val size = run("wm size")
        val physical = Regex("""Physical size:\s*(\S+)""").find(size)?.groupValues?.get(1) ?: ""
        val override = Regex("""Override size:\s*(\S+)""").find(size)?.groupValues?.get(1)
        if (physical.isNotBlank()) {
            items += if (override != null && override != physical) {
                Item("Screen resolution", "$physical, forced to $override", Status.WARN, "Someone changed the resolution setting.")
            } else {
                Item("Screen resolution", physical, Status.INFO)
            }
        }
        val display = run("dumpsys display")
        val fps = Regex("""fps=(\d+(?:\.\d+)?)""").findAll(display).mapNotNull { it.groupValues[1].toFloatOrNull() }.maxOrNull()
        if (fps != null) items += Item("Refresh rate", "${fps.toInt()} Hz max", Status.INFO)

        // Android 15+ phones can say outright whether the screen is the factory one.
        val partStatus = Regex("""screenPartStatus=(\w+)""").find(run("dumpsys SurfaceFlinger --display-id"))?.groupValues?.get(1)
        when (partStatus) {
            "ORIGINAL" -> items += Item("Screen part", "Original screen", Status.PASS, "The phone reports its factory-fitted screen.")
            "REPLACED" -> items += Item("Screen part", "Screen replaced", Status.FAIL, "The phone reports that its screen is not the factory-fitted one.")
        }

        val panelProps = props.filter { (k, v) ->
            v.isNotBlank() && Regex("""panel|lcd_?type|lcdtype""", RegexOption.IGNORE_CASE).containsMatchIn(k) &&
                !k.contains("persist.sys") && !k.startsWith("ro.build.") && v.length < 80
        }.values.distinct().take(2)
        val panelSysfs = run("cat /sys/class/lcd/panel/lcd_type /sys/class/lcd/panel/window_type 2>/dev/null").lines().filter { it.isNotBlank() }
        val panel = (panelSysfs + panelProps).distinct().joinToString(", ")
        items += if (panel.isNotBlank()) {
            Item("Screen panel ID", panel, Status.INFO, "The panel the phone detects. Compare with the original part for this model to spot a replaced screen.")
        } else {
            Item("Screen part", "Cannot be confirmed by software", Status.INFO, "This phone does not report its screen part. Check by eye for colour tint, uneven brightness and dead touch areas.")
        }

        // Sensors: declared by the phone but missing from the live list = dead or disconnected part.
        val sensors = run("dumpsys sensorservice | head -120").lowercase(Locale.US)
        if (sensors.isNotBlank()) {
            val wanted = listOf(
                Triple("android.hardware.sensor.accelerometer", "accel", "Motion sensor"),
                Triple("android.hardware.sensor.gyroscope", "gyro", "Gyroscope"),
                Triple("android.hardware.sensor.proximity", "prox", "Proximity sensor"),
                Triple("android.hardware.sensor.light", "light", "Light sensor"),
                Triple("android.hardware.sensor.compass", "mag", "Compass"),
            )
            for ((feature, key, label) in wanted) {
                if (feature !in features) continue
                items += if (sensors.contains(key)) Item(label, "Working", Status.PASS)
                else Item(label, "Not responding", Status.FAIL, "The phone should have this sensor but it is not answering. Common after a poor screen or board repair.")
            }
        }

        // Cameras
        val cam = run("dumpsys media.camera | grep -iE 'Number of camera devices|Number of normal camera devices'")
        val camCount = Regex("""Number of camera devices:\s*(\d+)""").find(cam)?.groupValues?.get(1)?.toIntOrNull()
        if (camCount != null) {
            val expectsCamera = "android.hardware.camera" in features || "android.hardware.camera.any" in features
            items += if (camCount == 0 && expectsCamera) {
                Item("Cameras", "None detected", Status.FAIL, "The phone cannot see any camera module.")
            } else {
                Item("Cameras", "$camCount detected", Status.PASS)
            }
        }

        // Fingerprint
        if ("android.hardware.fingerprint" in features) {
            val fp = run("dumpsys fingerprint | head -c 1500")
            items += if (fp.contains("prints", true) || fp.contains("enroll", true) || fp.contains("sensor", true)) {
                Item("Fingerprint reader", "Responding", Status.PASS)
            } else {
                Item("Fingerprint reader", "Not responding", Status.WARN, "Often stops working after a non-original screen or a board repair.")
            }
        }

        // Storage wear
        val life = run(
            "cat /sys/class/block/mmcblk0/device/life_time 2>/dev/null; " +
                "cat /sys/devices/platform/soc/*ufs*/health_descriptor/life_time_estimation_a 2>/dev/null",
        )
        Regex("""0x([0-9a-fA-F]{2})""").find(life)?.groupValues?.get(1)?.toIntOrNull(16)?.takeIf { it in 1..11 }?.let { v ->
            val used = (v * 10).coerceAtMost(100)
            items += Item(
                "Storage wear", "About $used% of its life used",
                if (v >= 9) Status.FAIL else if (v >= 7) Status.WARN else Status.PASS,
                if (v >= 7) "The storage chip is wearing out." else null,
            )
        }

        // Crashes
        val crashes = run("dumpsys dropbox | grep -cE 'system_server_crash|SYSTEM_TOMBSTONE|system_server_watchdog|SYSTEM_LAST_KMSG'").toIntOrNull()
        if (crashes != null) {
            items += Item(
                "System crashes", "$crashes recorded",
                if (crashes > 25) Status.WARN else Status.PASS,
                if (crashes > 25) "Many system crashes in the phone's log. Can point to a hardware fault." else null,
            )
        }
        return items
    }

    // ---------------------------------------------------------------- overview

    private fun overview(battery: BatteryResult): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        fun add(k: String, v: String) { if (v.isNotBlank()) out += k to v }

        val ramKb = Regex("""MemTotal:\s*(\d+)""").find(run("cat /proc/meminfo | head -3"))?.groupValues?.get(1)?.toLongOrNull()
        val dataKb = run("df -k /data | tail -1").split(Regex("\\s+")).getOrNull(1)?.toLongOrNull()
        val cores = run("grep -c ^processor /proc/cpuinfo").toIntOrNull()
        val blocks = run("ls /sys/class/block")
        val storageType = when {
            p("ro.boot.bootdevice").contains("ufs", true) || Regex("""\bsda\b""").containsMatchIn(blocks) -> "UFS"
            blocks.contains("mmcblk0") -> "eMMC"
            blocks.contains("nvme") -> "NVMe"
            else -> ""
        }
        val imei = imei()
        val rooted = packages.let { pk -> ROOT_PACKAGES.any { pk.contains("package:$it") } } || run("which su 2>/dev/null").contains("su")

        add("Model", listOf(marketName(), p("ro.product.model")).filter { it.isNotBlank() }.distinct().joinToString(" / "))
        add("IMEI", imei.ifBlank { "Dial *#06# on the phone" })
        add("Serial number", p("ro.serialno", "ro.boot.serialno"))
        add("Made on", p("ril.rfcal_date", "ro.boot.rf_cal_date", "ro.ril.oem.mfgdate"))
        if (ramKb != null) add("Memory", "${roundGb(ramKb)} GB")
        if (dataKb != null) add("Storage", "${marketedGb(dataKb)} GB")
        add("Sold for", p("ro.csc.country_code", "ro.csc.sales_code", "ro.miui.region", "ro.boot.hwc"))
        add("Android version", p("ro.build.version.release"))
        add("Security patch", p("ro.build.version.security_patch"))
        add("Chipset", socName())
        add("Build number", p("ro.build.display.id"))
        return out
    }

    private fun roundGb(kb: Long): Int {
        val gb = kb / 1024.0 / 1024.0
        return listOf(1, 2, 3, 4, 6, 8, 12, 16, 18, 24, 32).firstOrNull { it >= gb } ?: gb.toInt()
    }

    private fun marketedGb(dataKb: Long): Int {
        val gb = dataKb / 1024.0 / 1024.0
        return listOf(8, 16, 32, 64, 128, 256, 512, 1024, 2048).firstOrNull { it >= gb } ?: gb.toInt()
    }

    companion object {
        private val SDK_TO_RELEASE = mapOf(
            24 to 7, 25 to 7, 26 to 8, 27 to 8, 28 to 9, 29 to 10, 30 to 11,
            31 to 12, 32 to 12, 33 to 13, 34 to 14, 35 to 15, 36 to 16, 37 to 17,
        )
        private val ROOT_PACKAGES = listOf(
            "com.topjohnwu.magisk", "eu.chainfire.supersu", "me.weishu.kernelsu",
            "org.lsposed.manager", "com.koushikdutta.superuser", "me.bmax.apatch",
        )
    }
}
