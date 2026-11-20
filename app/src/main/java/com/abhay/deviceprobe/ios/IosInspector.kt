package com.abhay.deviceprobe.ios

import com.abhay.deviceprobe.Item
import com.abhay.deviceprobe.Report
import com.abhay.deviceprobe.Section
import com.abhay.deviceprobe.Status
import java.util.Locale

/** Runs the checks on a connected, trusted iPhone or iPad. */
class IosInspector(
    private val ld: Lockdown,
    private val progress: (String) -> Unit,
) {
    private var info: Dict = emptyMap()

    private fun s(key: String): String = (info[key] as? String)?.trim() ?: ""

    @Suppress("UNCHECKED_CAST")
    private fun domain(name: String): Dict = ld.value(name) as? Dict ?: emptyMap()

    @Suppress("UNCHECKED_CAST")
    private fun ioRegistry(entryClass: String): Dict = try {
        ld.service("com.apple.mobile.diagnostics_relay").use { svc ->
            PlistIo.send(svc.output, mapOf("Request" to "IORegistry", "EntryClass" to entryClass))
            val r = PlistIo.receive(svc.input)
            try { PlistIo.send(svc.output, mapOf("Request" to "Goodbye")) } catch (_: Exception) {}
            (r["Diagnostics"] as? Dict)?.get("IORegistry") as? Dict ?: emptyMap()
        }
    } catch (e: Exception) {
        emptyMap()
    }

    @Suppress("UNCHECKED_CAST")
    fun inspect(): Report {
        progress("Reading device identity")
        info = ld.value() as? Dict ?: throw IllegalStateException("The iPhone did not answer. Unlock it and try again.")
        val kind = s("DeviceClass").ifBlank { "iPhone" }

        val identity = identity(kind)
        progress("Checking locks and security")
        val locks = locks(kind)
        progress("Checking battery")
        val battery = battery(kind)
        progress("Checking parts")
        val parts = parts(kind)
        progress("Building report")

        val name = marketName()
        return Report(
            deviceName = "Apple $name",
            overview = overview(name),
            sections = listOf(
                Section("Is it a genuine $kind?", identity.filter { it.label != "Software version" }),
                Section("Locks that can block you", locks),
                Section("Battery", battery.filter { it.label in BUYER_BATTERY || it.status == Status.WARN || it.status == Status.FAIL }),
                Section("Screen and parts", parts),
            ).filter { it.items.isNotEmpty() },
            details = listOf(
                Section("Authenticity", identity),
                Section("Locks and security", locks),
                Section("Battery", battery),
                Section("Screen and parts", parts),
            ).filter { it.items.isNotEmpty() },
        )
    }

    private fun marketName(): String = MODELS[s("ProductType")] ?: s("ProductType").ifBlank { "device" }

    // ---------------------------------------------------------------- identity

    private fun identity(kind: String): List<Item> {
        val items = mutableListOf<Item>()
        items += Item(
            "Genuine device", "Yes, a real Apple ${marketName()}", Status.PASS,
            "It answered with Apple's own device protocol and reports itself as ${s("ProductType")}. Android-based copies cannot do this.",
        )

        // The first letter of the model number says how Apple first sold this unit.
        val model = s("ModelNumber")
        when (model.firstOrNull()) {
            'M' -> items += Item("How it was sold", "New, retail unit", Status.PASS, "Model number $model.")
            'F' -> items += Item("How it was sold", "Refurbished by Apple", Status.WARN, "Model number $model starts with F: Apple refurbished this unit. Fine to buy, but it is not a first-sale phone.")
            'N' -> items += Item("How it was sold", "Replacement unit", Status.WARN, "Model number $model starts with N: Apple gave this out as a service replacement.")
            'P' -> items += Item("How it was sold", "Personalised (engraved) unit", Status.INFO, "Model number $model.")
            '3' -> items += Item("How it was sold", "Shop demo unit", Status.WARN, "Model number $model starts with 3: this was a display unit in a shop.")
        }

        val activation = s("ActivationState")
        items += when {
            activation == "Activated" -> Item("Activation", "Activated", Status.PASS)
            activation == "Unactivated" -> Item(
                "Activation", "Not activated", Status.WARN,
                "The $kind has not been set up. Go through setup with the seller: if it asks for someone's Apple ID, it is locked.",
            )
            activation.isBlank() -> Item("Activation", "Not reported", Status.INFO)
            else -> Item("Activation", activation, Status.INFO)
        }

        // A second file service that only exists on jailbroken devices.
        val jailbroken = try {
            ld.service("com.apple.afc2").close()
            true
        } catch (e: Exception) {
            false
        }
        items += if (jailbroken) {
            Item("Jailbreak", "Jailbroken", Status.FAIL, "Apple's software protection was removed. Banking apps can refuse to work and the warranty is void.")
        } else {
            Item("Jailbreak", "No jailbreak found", Status.PASS)
        }

        if (s("ProductVersion").isNotBlank()) {
            items += Item("Software version", "${if (kind == "iPad") "iPadOS" else "iOS"} ${s("ProductVersion")} (${s("BuildVersion")})", Status.INFO)
        }
        return items
    }

    // ------------------------------------------------------------------- locks

    private fun locks(kind: String): List<Item> {
        val items = mutableListOf<Item>()

        // Older iOS answers in the Find My domain; newer iOS only leaves a flag in the phone's
        // boot settings, next to a masked copy of the Apple ID it is locked to.
        @Suppress("UNCHECKED_CAST")
        val nvram = info["NonVolatileRAM"] as? Dict ?: emptyMap()
        fun nv(key: String) = (nvram[key] as? ByteArray)?.let { String(it, Charsets.UTF_8).trim() }
        val findMy = domain("com.apple.fmip")["IsAssociated"] as? Boolean
            ?: nv("fm-activation-locked")?.let { it.equals("YES", true) }
        val account = nv("fm-account-masked")?.takeIf { it.isNotBlank() }
        items += when (findMy) {
            true -> Item(
                "Activation Lock (Find My)", "Find My is on", Status.WARN,
                "The $kind is tied to the seller's Apple ID${if (account != null) " ($account)" else ""}. If it is reset now it will be locked to them. " +
                    "Have the seller sign out in Settings > their name > Sign Out before you pay.",
            )
            false -> Item("Activation Lock (Find My)", "Off, not tied to an Apple ID", Status.PASS)
            null -> Item(
                "Activation Lock (Find My)", "Could not read", Status.INFO,
                "Check on the $kind: Settings > the name at the top > Find My should be off.",
            )
        }

        val supervised = domain("com.apple.mobile.chaperone")["DeviceIsChaperoned"] as? Boolean
        items += when (supervised) {
            true -> Item(
                "Company management (MDM)", "Supervised by an organisation", Status.FAIL,
                "The $kind is controlled remotely by a company or school. They can lock or wipe it at any time.",
            )
            false -> Item("Company management (MDM)", "Not managed", Status.PASS)
            null -> Item(
                "Company management (MDM)", "Could not read", Status.INFO,
                "Check on the $kind: Settings > General > VPN & Device Management should show no management profile.",
            )
        }

        (info["PasswordProtected"] as? Boolean)?.let {
            items += Item("Passcode", if (it) "Set" else "None", Status.INFO, if (it) "Have the seller remove the passcode or erase the $kind before handing it over." else null)
        }
        return items
    }

    // ----------------------------------------------------------------- battery

    @Suppress("UNCHECKED_CAST")
    private fun battery(kind: String): List<Item> {
        val items = mutableListOf<Item>()
        val reg = ioRegistry("IOPMPowerSource")
        val data = reg["BatteryData"] as? Dict ?: emptyMap()
        fun num(vararg keys: String): Long? {
            for (k in keys) {
                ((reg[k] ?: data[k]) as? Number)?.toLong()?.let { return it }
            }
            return null
        }

        val design = num("DesignCapacity")?.takeIf { it > 0 }
        val full = num("AppleRawMaxCapacity", "NominalChargeCapacity")?.takeIf { it > 0 }
        val health = if (design != null && full != null) (full * 100 / design).toInt().takeIf { it in 20..130 }?.coerceAtMost(100) else null
        items += when {
            health == null -> Item("Battery life", "Not reported", Status.INFO, "Check on the $kind: Settings > Battery > Battery Health.")
            health >= 85 -> Item("Battery life", "$health%", Status.PASS, "Share of the original capacity the battery still holds.")
            health >= 80 -> Item("Battery life", "$health%", Status.WARN, "Noticeably worn. Apple advises replacement below 80%.")
            else -> Item("Battery life", "$health%", Status.FAIL, "Badly worn. Plan to replace the battery.")
        }

        // iPhone 15 and later are rated for 1000 cycles, earlier ones for 500.
        val type = s("ProductType")
        val major = Regex("""iPhone(\d+),""").find(type)?.groupValues?.get(1)?.toIntOrNull()
        val rated = if (major != null && (major >= 16 || type == "iPhone15,4" || type == "iPhone15,5")) 1000 else 500
        val cycles = num("CycleCount")?.toInt()?.takeIf { it >= 0 }
        items += when {
            cycles == null -> Item("Charge cycles", "Not reported", Status.INFO)
            cycles > rated -> Item("Charge cycles", "$cycles", Status.WARN, "Heavy use. This battery is rated for $rated cycles.")
            else -> Item("Charge cycles", "$cycles", Status.PASS, "Rated for $rated cycles.")
        }

        num("Temperature")?.let { t ->
            val c = t / 100.0
            if (c in -20.0..90.0) {
                items += Item("Temperature", String.format(Locale.US, "%.1f °C", c), if (c > 45) Status.WARN else Status.PASS, if (c > 45) "Hot for a phone at rest." else null)
            }
        }
        num("Voltage")?.let { items += Item("Voltage", String.format(Locale.US, "%.2f V", it / 1000.0), Status.INFO) }
        if (design != null) items += Item("Design capacity", "$design mAh", Status.INFO)
        if (full != null) items += Item("Full charge now", "$full mAh", Status.INFO)
        val level = (domain("com.apple.mobile.battery")["BatteryCurrentCapacity"] as? Number)?.toInt() ?: num("CurrentCapacity")?.toInt()
        if (level != null && level in 0..100) items += Item("Charge now", "$level%", Status.INFO)
        (reg["Serial"] as? String ?: data["Serial"] as? String)?.takeIf { it.isNotBlank() }?.let {
            items += Item("Battery serial", it, Status.INFO)
        }
        return items
    }

    // ------------------------------------------------------------------- parts

    private fun parts(kind: String): List<Item> {
        val items = mutableListOf<Item>()
        items += Item(
            "Screen, camera and battery parts", "Check on the $kind itself", Status.INFO,
            "Open Settings > General > About. \"Parts and Service History\" lists any replaced part; \"Unknown Part\" means it is not genuine. No such section means nothing was replaced.",
        )

        val cellular = info["TelephonyCapability"] as? Boolean ?: (kind == "iPhone")
        if (cellular) {
            val imei = s("InternationalMobileEquipmentIdentity")
            items += if (s("BasebandVersion").isBlank() || imei.isBlank()) {
                Item(
                    "Mobile radio", "No baseband found", Status.FAIL,
                    "The $kind cannot see its modem. Calls and mobile data will not work. This is a board fault that is expensive to repair.",
                )
            } else {
                Item("Mobile radio", "Working", Status.PASS)
            }
        }
        items += if (s("WiFiAddress").isBlank()) {
            Item("Wi-Fi chip", "Not responding", Status.FAIL, "The $kind does not report a Wi-Fi address. Wi-Fi is probably dead (a known board fault).")
        } else {
            Item("Wi-Fi chip", "Working", Status.PASS)
        }
        items += if (s("BluetoothAddress").isBlank()) {
            Item("Bluetooth chip", "Not responding", Status.FAIL, "The $kind does not report a Bluetooth address.")
        } else {
            Item("Bluetooth chip", "Working", Status.PASS)
        }
        return items
    }

    // ---------------------------------------------------------------- overview

    private fun overview(name: String): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        fun add(k: String, v: String) { if (v.isNotBlank()) out += k to v }
        val disk = (domain("com.apple.disk_usage")["TotalDiskCapacity"] as? Number)?.toLong()
        val region = s("RegionInfo")

        add("Model", listOf(name, s("ProductType")).filter { it.isNotBlank() }.distinct().joinToString(" / "))
        add("Model number", s("ModelNumber") + region)
        add("IMEI", s("InternationalMobileEquipmentIdentity"))
        add("IMEI 2", s("InternationalMobileEquipmentIdentity2"))
        add("Serial number", s("SerialNumber"))
        if (disk != null && disk > 0) {
            val gb = disk / 1_000_000_000.0
            add("Storage", "${listOf(16, 32, 64, 128, 256, 512, 1024, 2048).firstOrNull { it >= gb - 1 } ?: gb.toInt()} GB")
        }
        add("Sold for", REGIONS[region.substringBefore('/')] ?: region)
        add("Software version", s("ProductVersion"))
        add("Build number", s("BuildVersion"))
        add("Name on device", s("DeviceName"))
        return out
    }

    companion object {
        private val BUYER_BATTERY = setOf("Battery life", "Charge cycles")

        private val REGIONS = mapOf(
            "HN" to "India", "LL" to "United States", "ZP" to "Hong Kong / Macau", "CH" to "China", "J" to "Japan",
            "B" to "United Kingdom / Ireland", "ZA" to "Singapore", "AE" to "UAE", "AH" to "Gulf countries",
            "X" to "Australia / New Zealand", "KH" to "South Korea", "C" to "Canada", "VC" to "Canada",
            "ZD" to "Europe", "D" to "Germany", "FD" to "Europe", "QN" to "Nordic countries", "TH" to "Thailand",
            "VN" to "Vietnam", "ID" to "Indonesia", "PA" to "Indonesia", "MY" to "Malaysia", "TA" to "Taiwan",
            "RU" to "Russia", "E" to "Mexico", "BZ" to "Brazil", "QL" to "Italy / Spain / Portugal", "F" to "France",
        )

        private val MODELS = mapOf(
            "iPhone8,1" to "iPhone 6s", "iPhone8,2" to "iPhone 6s Plus", "iPhone8,4" to "iPhone SE",
            "iPhone9,1" to "iPhone 7", "iPhone9,3" to "iPhone 7", "iPhone9,2" to "iPhone 7 Plus", "iPhone9,4" to "iPhone 7 Plus",
            "iPhone10,1" to "iPhone 8", "iPhone10,4" to "iPhone 8", "iPhone10,2" to "iPhone 8 Plus", "iPhone10,5" to "iPhone 8 Plus",
            "iPhone10,3" to "iPhone X", "iPhone10,6" to "iPhone X",
            "iPhone11,2" to "iPhone XS", "iPhone11,4" to "iPhone XS Max", "iPhone11,6" to "iPhone XS Max", "iPhone11,8" to "iPhone XR",
            "iPhone12,1" to "iPhone 11", "iPhone12,3" to "iPhone 11 Pro", "iPhone12,5" to "iPhone 11 Pro Max", "iPhone12,8" to "iPhone SE (2nd generation)",
            "iPhone13,1" to "iPhone 12 mini", "iPhone13,2" to "iPhone 12", "iPhone13,3" to "iPhone 12 Pro", "iPhone13,4" to "iPhone 12 Pro Max",
            "iPhone14,4" to "iPhone 13 mini", "iPhone14,5" to "iPhone 13", "iPhone14,2" to "iPhone 13 Pro", "iPhone14,3" to "iPhone 13 Pro Max",
            "iPhone14,6" to "iPhone SE (3rd generation)", "iPhone14,7" to "iPhone 14", "iPhone14,8" to "iPhone 14 Plus",
            "iPhone15,2" to "iPhone 14 Pro", "iPhone15,3" to "iPhone 14 Pro Max", "iPhone15,4" to "iPhone 15", "iPhone15,5" to "iPhone 15 Plus",
            "iPhone16,1" to "iPhone 15 Pro", "iPhone16,2" to "iPhone 15 Pro Max",
            "iPhone17,3" to "iPhone 16", "iPhone17,4" to "iPhone 16 Plus", "iPhone17,1" to "iPhone 16 Pro", "iPhone17,2" to "iPhone 16 Pro Max",
            "iPhone17,5" to "iPhone 16e",
            "iPhone18,3" to "iPhone 17", "iPhone18,1" to "iPhone 17 Pro", "iPhone18,2" to "iPhone 17 Pro Max", "iPhone18,4" to "iPhone Air",
        )
    }
}
