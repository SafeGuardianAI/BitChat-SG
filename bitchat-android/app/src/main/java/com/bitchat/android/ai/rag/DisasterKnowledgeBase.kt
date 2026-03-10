package com.bitchat.android.ai.rag

/**
 * Embedded Disaster Knowledge Base
 *
 * Contains structured disaster preparedness and response data
 * organized by category. This knowledge is embedded directly in
 * the app so it works 100% offline — critical during disasters
 * when connectivity is unavailable.
 *
 * Data sourced from FEMA, Red Cross, and WHO public guidelines.
 */
object DisasterKnowledgeBase {

    data class KnowledgeEntry(
        val id: String,
        val category: String,
        val title: String,
        val content: String,
        val keywords: Set<String>
    )

    val entries: List<KnowledgeEntry> = listOf(
        // ─── EARTHQUAKE ───
        KnowledgeEntry(
            id = "eq_during",
            category = "earthquake",
            title = "During an Earthquake",
            content = "DROP to your hands and knees. Take COVER under a sturdy desk or table. HOLD ON until the shaking stops. If no shelter is available, cover your head and neck with your arms. Stay away from windows, outside walls, and anything that could fall. If outdoors, move to a clear area away from buildings, trees, and power lines. If in a vehicle, pull over to a clear area and stay inside with seatbelt fastened.",
            keywords = setOf("earthquake", "shaking", "drop", "cover", "hold", "tremor", "quake", "seismic")
        ),
        KnowledgeEntry(
            id = "eq_after",
            category = "earthquake",
            title = "After an Earthquake",
            content = "Expect aftershocks — they can be strong. Check yourself and others for injuries. If trapped, tap on a pipe or wall so rescuers can find you. Do not use elevators. Check for gas leaks (smell, hissing sound) and turn off gas if suspected. Check for structural damage before re-entering buildings. Use text messages instead of phone calls to keep lines clear for emergencies. Turn on a battery-powered radio for updates.",
            keywords = setOf("earthquake", "aftershock", "trapped", "gas leak", "damage", "rescue", "aftershocks")
        ),
        KnowledgeEntry(
            id = "eq_prepare",
            category = "earthquake",
            title = "Earthquake Preparedness",
            content = "Secure heavy furniture to walls. Store emergency supplies: water (1 gallon per person per day for 3 days), non-perishable food, flashlight, batteries, first aid kit, medications, important documents in waterproof container. Know how to shut off gas, electricity, and water. Identify safe spots in each room. Practice Drop-Cover-Hold drills. Keep shoes and a flashlight near your bed.",
            keywords = setOf("earthquake", "prepare", "supply", "emergency kit", "drill", "secure", "preparedness")
        ),

        // ─── FLOOD ───
        KnowledgeEntry(
            id = "fl_during",
            category = "flood",
            title = "During a Flood",
            content = "Move immediately to higher ground. Do NOT walk, swim, or drive through flood waters. Just 6 inches of moving water can knock you down. 1 foot of water can sweep away a vehicle. Turn around, don't drown. If trapped in a building, go to the highest level but do NOT climb into a closed attic — you may become trapped. Go on the roof if necessary and signal for help. Avoid contact with floodwater — it may be contaminated with sewage, chemicals, or debris.",
            keywords = setOf("flood", "water", "higher ground", "drowning", "flash flood", "rising water", "submerged")
        ),
        KnowledgeEntry(
            id = "fl_after",
            category = "flood",
            title = "After a Flood",
            content = "Return home only when authorities say it is safe. Photograph damage for insurance. Avoid walking or driving through remaining floodwater. Be aware of hazards like weakened roads, contaminated water, gas leaks, damaged electrical wires, and unstable structures. Clean and disinfect everything that got wet. Throw away food that came in contact with floodwater. Pump out flooded basements gradually (1/3 of water per day) to avoid structural damage.",
            keywords = setOf("flood", "cleanup", "contaminated", "damage", "return", "disinfect", "floodwater")
        ),

        // ─── FIRE ───
        KnowledgeEntry(
            id = "fr_escape",
            category = "fire",
            title = "Fire Escape",
            content = "Get out, stay out, and call for help. Crawl low under smoke — cleaner air is near the floor. Before opening any door, feel it with the back of your hand. If hot, use another way out. If clothing catches fire: STOP, DROP, and ROLL. Close doors behind you to slow fire spread. Use stairs, never elevators. If you cannot escape, close the door, seal cracks with wet towels, and signal from a window.",
            keywords = setOf("fire", "escape", "smoke", "burn", "evacuate", "flames", "exit")
        ),
        KnowledgeEntry(
            id = "fr_wildfire",
            category = "fire",
            title = "Wildfire Safety",
            content = "Evacuate immediately when ordered. Wear protective clothing: long sleeves, long pants, sturdy shoes, cotton or wool (not synthetic). Wet bandana over nose and mouth. Close all windows and doors. Remove flammable items from around house. Turn on lights so house is visible in smoke. If trapped in a vehicle, park in an area clear of vegetation, close windows, cover yourself with a blanket, lie on the floor.",
            keywords = setOf("wildfire", "fire", "evacuate", "smoke", "brush fire", "forest fire", "burning")
        ),

        // ─── FIRST AID ───
        KnowledgeEntry(
            id = "fa_bleeding",
            category = "first_aid",
            title = "Controlling Severe Bleeding",
            content = "Apply direct pressure with a clean cloth. If blood soaks through, add more cloth on top — do NOT remove the first layer. Elevate the injured area above the heart if possible. For life-threatening limb bleeding, apply a tourniquet 2-3 inches above the wound (not over a joint). Note the time of application. Keep the person warm and calm. Call for medical help immediately.",
            keywords = setOf("bleeding", "blood", "wound", "tourniquet", "first aid", "cut", "injury", "medical")
        ),
        KnowledgeEntry(
            id = "fa_cpr",
            category = "first_aid",
            title = "CPR Instructions",
            content = "Check responsiveness: tap shoulders and shout. Call for help. Place person on their back on a firm surface. Place heel of one hand on center of chest, other hand on top. Push hard and fast: at least 2 inches deep, 100-120 compressions per minute. Allow full chest recoil between compressions. If trained, give 2 rescue breaths after every 30 compressions. Continue until professional help arrives or an AED is available.",
            keywords = setOf("cpr", "heart", "breathing", "unconscious", "cardiac", "first aid", "resuscitation", "medical")
        ),
        KnowledgeEntry(
            id = "fa_fracture",
            category = "first_aid",
            title = "Treating Fractures",
            content = "Do not try to straighten the bone. Immobilize the injury: splint the area above and below the fracture using rigid materials (sticks, boards, rolled newspaper). Pad the splint for comfort. Apply ice wrapped in cloth (not directly on skin) for 20 minutes at a time. Treat for shock: lay person flat, elevate legs, keep warm. Watch for signs of circulation loss below injury (numbness, pale/blue color). Seek medical attention.",
            keywords = setOf("fracture", "broken bone", "splint", "injury", "medical", "first aid", "broken")
        ),

        // ─── SHELTER ───
        KnowledgeEntry(
            id = "sh_improvised",
            category = "shelter",
            title = "Improvised Shelter",
            content = "Priority: protection from wind, rain, and ground cold. Lean-to shelter: prop a long branch against a tree, lay smaller branches at 45 degrees, cover with leaves/debris. A-frame: two forked sticks with ridgepole, layer branches and debris. Ground insulation is critical — pile at least 6 inches of dry leaves, pine needles, or grass beneath you. In urban settings, use intact doorways, overhangs, or vehicles. Avoid low-lying areas prone to flooding.",
            keywords = setOf("shelter", "survival", "camping", "protection", "cold", "rain", "emergency shelter")
        ),
        KnowledgeEntry(
            id = "sh_warmth",
            category = "shelter",
            title = "Staying Warm in Emergencies",
            content = "Layer clothing — trapped air insulates. Keep head, hands, and feet covered (50% of heat loss is from the head). Stay dry — wet clothing loses 90% of insulating value. Stuff newspaper, leaves, or other materials between clothing layers. Huddle with others for shared body warmth. Use a space blanket (reflective side toward body). Build a small fire safely with proper ventilation. Stay active to generate body heat but avoid sweating.",
            keywords = setOf("cold", "warmth", "hypothermia", "freezing", "winter", "shelter", "heat", "temperature")
        ),

        // ─── WATER ───
        KnowledgeEntry(
            id = "wt_purify",
            category = "water",
            title = "Water Purification",
            content = "Boiling is the most reliable method: bring water to a rolling boil for at least 1 minute (3 minutes at elevations above 6,500 feet). Chemical treatment: add 8 drops (1/8 teaspoon) of unscented household bleach per gallon of clear water; 16 drops for cloudy water. Wait 30 minutes. Water should have slight chlorine smell. If not, repeat and wait 15 more minutes. Filter cloudy water through a clean cloth first. Solar disinfection (SODIS): fill clear plastic bottles, expose to direct sunlight for 6+ hours.",
            keywords = setOf("water", "purify", "boil", "filter", "drink", "safe water", "contaminated", "purification")
        ),
        KnowledgeEntry(
            id = "wt_find",
            category = "water",
            title = "Finding Water in Emergencies",
            content = "Priority sources: water heater tanks (turn off gas/electric first, open drain valve), toilet tanks (not bowls), ice cubes. Outdoor: collect rainwater, morning dew with cloth, follow animal tracks or insect swarms to water. Listen for flowing water. Avoid water near industrial sites, stagnant water, or water with unusual color/smell. Ration water if supply is limited — do not wait until you run out to seek more.",
            keywords = setOf("water", "find water", "dehydration", "thirst", "water source", "survival", "drinking")
        ),

        // ─── COMMUNICATION ───
        KnowledgeEntry(
            id = "cm_mesh",
            category = "communication",
            title = "Mesh Network Communication",
            content = "SafeGuardian uses Bluetooth Low Energy mesh networking. Keep devices within range (typically 30-100 meters). Messages relay through intermediate devices automatically. Enable disaster mode for priority message handling. AI-generated responses are shared with nearby peers automatically. Save battery: reduce screen brightness, close unnecessary apps. Keep the app running in the foreground for best mesh connectivity.",
            keywords = setOf("mesh", "bluetooth", "communicate", "signal", "radio", "message", "network", "connection")
        ),
        KnowledgeEntry(
            id = "cm_signal",
            category = "communication",
            title = "Emergency Signaling",
            content = "Visual: three fires in a triangle, mirror flash toward aircraft, bright colored clothing/fabric. Audible: three short blasts (SOS) on a whistle, repeat every minute. Ground signals for aircraft: V means need assistance, X means need medical help, arrow means traveling this direction. Use phone flashlight in SOS pattern: three short, three long, three short. Conserve phone battery — airplane mode when not transmitting.",
            keywords = setOf("signal", "help", "sos", "rescue", "emergency", "communication", "found", "lost")
        ),

        // ─── TSUNAMI ───
        KnowledgeEntry(
            id = "ts_warning",
            category = "tsunami",
            title = "Tsunami Warning Signs",
            content = "Natural warning signs: strong earthquake lasting 20+ seconds near coast, unusual ocean behavior (rapid rise or draining of water), loud roaring sound from ocean. If you observe any of these, move immediately to high ground or at least 2 miles inland. Do NOT wait for official warnings. A tsunami may arrive within minutes of an earthquake. Do not go to the beach to watch — tsunami waves can be much larger than they appear.",
            keywords = setOf("tsunami", "wave", "coast", "ocean", "beach", "high ground", "earthquake")
        ),

        // ─── TORNADO ───
        KnowledgeEntry(
            id = "tn_safety",
            category = "tornado",
            title = "Tornado Safety",
            content = "Seek shelter in a basement or interior room on the lowest floor. Stay away from windows, doors, and outside walls. Get under sturdy furniture and cover your head. In a mobile home, leave immediately and seek sturdy shelter. If outside with no shelter, lie flat in a ditch and cover your head. Do NOT try to outrun a tornado in a vehicle. Warning signs: dark greenish sky, large hail, loud continuous roar, funnel cloud.",
            keywords = setOf("tornado", "twister", "wind", "funnel", "storm", "basement", "shelter")
        ),

        // ─── HURRICANE ───
        KnowledgeEntry(
            id = "hr_prepare",
            category = "hurricane",
            title = "Hurricane Preparedness",
            content = "Board up windows or install storm shutters. Stock supplies for at least 3 days. Fill bathtubs and large containers with water. Charge all devices and portable batteries. Fill vehicle gas tanks. Bring in outdoor furniture. Know your evacuation zone and route. Evacuate if ordered — do not wait. After the storm: avoid downed power lines, flooded roads, and weakened structures. Boil water until officials confirm safety.",
            keywords = setOf("hurricane", "storm", "wind", "surge", "evacuate", "cyclone", "tropical storm")
        )
    )

    /**
     * Get all category names.
     */
    fun getCategories(): Set<String> = entries.map { it.category }.toSet()

    /**
     * Get entries by category.
     */
    fun getByCategory(category: String): List<KnowledgeEntry> =
        entries.filter { it.category == category }

    /**
     * Get entry by ID.
     */
    fun getById(id: String): KnowledgeEntry? = entries.find { it.id == id }
}
