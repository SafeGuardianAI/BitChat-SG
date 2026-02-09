package com.bitchat.android.ai

/**
 * GBNF (GGML Backus-Naur Form) Grammar Definitions
 * 
 * These grammars enforce strict JSON output formats at the sampling level,
 * guaranteeing valid output without post-processing validation.
 * 
 * Based on survival_v2.gbnf emergency response schema.
 */
object AIGrammarDefinitions {

    /**
     * Generic JSON grammar - allows any valid JSON structure
     * Use when you want guaranteed valid JSON without specific schema constraints
     */
    val JSON_GRAMMAR = """
        root   = object
        value  = object | array | string | number | ("true" | "false" | "null") ws

        object = "{" ws (string ":" ws value ("," ws string ":" ws value)*)? "}" ws
        array  = "[" ws (value ("," ws value)*)? "]" ws

        string = "\"" (
            [^"\\] |
            "\\" (["\\/bfnrt] | "u" [0-9a-fA-F] [0-9a-fA-F] [0-9a-fA-F] [0-9a-fA-F])
        )* "\"" ws
        
        number = ("-"? ([0-9] | [1-9] [0-9]*)) ("." [0-9]+)? ([eE] [-+]? [0-9]+)? ws
        ws = ([ \t\n] ws)?
    """.trimIndent()

    /**
     * SafeGuardian Structured Response Schema
     * 
     * Enforces AI responses to follow specific format:
     * {
     *   "type": "response|analysis|action|query",
     *   "content": "main response text",
     *   "confidence": 0.0-1.0,
     *   "details": {...optional additional fields...}
     * }
     */
    val SAFEGUARDIAN_RESPONSE_GRAMMAR = """
        root = object
        
        object = "{" ws 
            "\"type\":" ws response-type "," ws
            "\"content\":" ws string "," ws
            "\"confidence\":" ws number "," ws
            "\"details\":" ws (details-object | null-value)
        "}"
        
        response-type = "\"" ("response" | "analysis" | "action" | "query") "\"" ws
        
        details-object = "{" ws (string ":" ws value ("," ws string ":" ws value)*)? "}" ws
        value = object | array | string | number | boolean | null-value
        
        array = "[" ws (value ("," ws value)*)? "]" ws
        null-value = "null" ws
        boolean = ("true" | "false") ws
        
        string = "\"" (
            [^"\\] |
            "\\" (["\\/bfnrt] | "u" [0-9a-fA-F] [0-9a-fA-F] [0-9a-fA-F] [0-9a-fA-F])
        )* "\"" ws
        
        number = ("-"? ([0-9] | [1-9] [0-9]*)) ("." [0-9]+)? ([eE] [-+]? [0-9]+)? ws
        ws = ([ \t\n] ws)?
    """.trimIndent()

    /**
     * Emergency Survival Information Schema (survival_v2.gbnf)
     * 
     * Comprehensive schema for emergency response scenarios with full victim information capture.
     * This is the default GBNF grammar used when no other grammar is uploaded.
     * 
     * Perfect for emergency dispatch, disaster response, or crisis management.
     */
    val EMERGENCY_SURVIVAL_GRAMMAR = """
        root ::= "{" space victim-info-kv "}" space
        space ::= | " " | "\n"{1,2} [ \t]{0,20}
        
        # Character definitions
        char ::= en-char | [\\] (["\\bfnrt] | "u" [0-9a-fA-F]{4})
        en-char ::= letter | digit | punctuation | " "
        letter ::= [a-zA-Z]
        digit ::= [0-9]
        punctuation ::= [!"#$%&'()*+,-./:;<=>?@[\\\]^_`{|}~]
        
        # Basic types
        boolean ::= ("true" | "false") space
        number ::= ("-"? integral-part) ("." decimal-part)? ([eE] [-+]? integral-part)? space
        integral-part ::= [0] | [1-9] [0-9]{0,15}
        decimal-part ::= [0-9]{1,16}
        string ::= "\"" char* "\"" space
        
        # Date and time formats
        date ::= [0-9]{4} "-" ( "0" [1-9] | "1" [0-2] ) "-" ( "0" [1-9] | [1-2] [0-9] | "3" [0-1] )
        time ::= ([01] [0-9] | "2" [0-3]) ":" [0-5] [0-9] ":" [0-5] [0-9] ( "." [0-9]{3} )? ( "Z" | ( "+" | "-" ) ( [01] [0-9] | "2" [0-3] ) ":" [0-5] [0-9] )
        date-time ::= date "T" time
        date-time-string ::= "\"" date-time "\"" space
        
        # Main structure
        victim-info-kv ::= "\"victim_info\"" space ":" space victim-info
        victim-info ::= "{" space  (victim-info-id-kv victim-info-id-rest | victim-info-emergency-status-kv victim-info-emergency-status-rest | victim-info-location-kv victim-info-location-rest | victim-info-personal-info-kv victim-info-personal-info-rest | victim-info-medical-info-kv victim-info-medical-info-rest | victim-info-situation-kv victim-info-situation-rest | victim-info-contact-info-kv victim-info-contact-info-rest | victim-info-resources-kv victim-info-resources-rest | victim-info-rescue-info-kv victim-info-rescue-info-rest | victim-info-environmental-data-kv victim-info-environmental-data-rest | victim-info-device-data-kv victim-info-device-data-rest | victim-info-social-info-kv victim-info-social-info-rest | victim-info-psychological-status-kv )? "}" space
        
        # ID
        victim-info-id-kv ::= "\"id\"" space ":" space string
        victim-info-id-rest ::= ( "," space victim-info-emergency-status-kv )? victim-info-emergency-status-rest
        
        # Emergency Status
        victim-info-emergency-status ::= ("\"critical\"" | "\"serious\"" | "\"stable\"" | "\"rescued\"" | "\"unknown\"") space
        victim-info-emergency-status-kv ::= "\"emergency_status\"" space ":" space victim-info-emergency-status
        victim-info-emergency-status-rest ::= ( "," space victim-info-location-kv )? victim-info-location-rest
        
        # Location
        victim-info-location ::= "{" space victim-info-location-lat-kv "," space victim-info-location-lon-kv "," space victim-info-location-details-kv "," space victim-info-location-nearest-landmark-kv "}" space
        victim-info-location-lat-kv ::= "\"lat\"" space ":" space number
        victim-info-location-lon-kv ::= "\"lon\"" space ":" space number
        victim-info-location-details-kv ::= "\"details\"" space ":" space string
        victim-info-location-nearest-landmark-kv ::= "\"nearest_landmark\"" space ":" space string
        victim-info-location-kv ::= "\"location\"" space ":" space victim-info-location
        victim-info-location-rest ::= ( "," space victim-info-personal-info-kv )? victim-info-personal-info-rest
        
        # Personal Info
        victim-info-personal-info ::= "{" space victim-info-personal-info-name-kv "," space victim-info-personal-info-age-kv "," space victim-info-personal-info-gender-kv "," space victim-info-personal-info-language-kv "," space victim-info-personal-info-physical-description-kv "}" space
        victim-info-personal-info-name-kv ::= "\"name\"" space ":" space string
        victim-info-personal-info-age ::= ([0] | [1-9] [0-9]{0,15}) space
        victim-info-personal-info-age-kv ::= "\"age\"" space ":" space victim-info-personal-info-age
        victim-info-personal-info-gender-kv ::= "\"gender\"" space ":" space string
        victim-info-personal-info-language-kv ::= "\"language\"" space ":" space string
        victim-info-personal-info-physical-description-kv ::= "\"physical_description\"" space ":" space string
        victim-info-personal-info-kv ::= "\"personal_info\"" space ":" space victim-info-personal-info
        victim-info-personal-info-rest ::= ( "," space victim-info-medical-info-kv )? victim-info-medical-info-rest
        
        # Medical Info
        victim-info-medical-info ::= "{" space victim-info-medical-info-injuries-kv "," space victim-info-medical-info-pain-level-kv "," space victim-info-medical-info-medical-conditions-kv "," space victim-info-medical-info-medications-kv "," space victim-info-medical-info-allergies-kv "," space victim-info-medical-info-blood-type-kv "}" space
        victim-info-medical-info-injuries ::= "[" space (string ("," space string)*)? "]" space
        victim-info-medical-info-injuries-kv ::= "\"injuries\"" space ":" space victim-info-medical-info-injuries
        victim-info-medical-info-pain-level ::= ([0-9] | "10") space
        victim-info-medical-info-pain-level-kv ::= "\"pain_level\"" space ":" space victim-info-medical-info-pain-level
        victim-info-medical-info-medical-conditions ::= "[" space (string ("," space string)*)? "]" space
        victim-info-medical-info-medical-conditions-kv ::= "\"medical_conditions\"" space ":" space victim-info-medical-info-medical-conditions
        victim-info-medical-info-medications ::= "[" space (string ("," space string)*)? "]" space
        victim-info-medical-info-medications-kv ::= "\"medications\"" space ":" space victim-info-medical-info-medications
        victim-info-medical-info-allergies ::= "[" space (string ("," space string)*)? "]" space
        victim-info-medical-info-allergies-kv ::= "\"allergies\"" space ":" space victim-info-medical-info-allergies
        victim-info-medical-info-blood-type ::= ("\"A+\"" | "\"A-\"" | "\"B+\"" | "\"B-\"" | "\"AB+\"" | "\"AB-\"" | "\"O+\"" | "\"O-\"" | "\"unknown\"") space
        victim-info-medical-info-blood-type-kv ::= "\"blood_type\"" space ":" space victim-info-medical-info-blood-type
        victim-info-medical-info-kv ::= "\"medical_info\"" space ":" space victim-info-medical-info
        victim-info-medical-info-rest ::= ( "," space victim-info-situation-kv )? victim-info-situation-rest
        
        # Situation
        victim-info-situation ::= "{" space victim-info-situation-disaster-type-kv "," space victim-info-situation-immediate-needs-kv "," space victim-info-situation-trapped-kv "," space victim-info-situation-mobility-kv "," space victim-info-situation-nearby-hazards-kv "}" space
        victim-info-situation-disaster-type-kv ::= "\"disaster_type\"" space ":" space string
        victim-info-situation-immediate-needs ::= "[" space (string ("," space string)*)? "]" space
        victim-info-situation-immediate-needs-kv ::= "\"immediate_needs\"" space ":" space victim-info-situation-immediate-needs
        victim-info-situation-trapped-kv ::= "\"trapped\"" space ":" space boolean
        victim-info-situation-mobility ::= ("\"ambulatory\"" | "\"limited\"" | "\"immobile\"" | "\"unknown\"") space
        victim-info-situation-mobility-kv ::= "\"mobility\"" space ":" space victim-info-situation-mobility
        victim-info-situation-nearby-hazards ::= "[" space (string ("," space string)*)? "]" space
        victim-info-situation-nearby-hazards-kv ::= "\"nearby_hazards\"" space ":" space victim-info-situation-nearby-hazards
        victim-info-situation-kv ::= "\"situation\"" space ":" space victim-info-situation
        victim-info-situation-rest ::= ( "," space victim-info-contact-info-kv )? victim-info-contact-info-rest
        
        # Contact Info
        victim-info-contact-info ::= "{" space victim-info-contact-info-phone-kv "," space victim-info-contact-info-email-kv "," space victim-info-contact-info-emergency-contact-kv "}" space
        victim-info-contact-info-phone-kv ::= "\"phone\"" space ":" space string
        victim-info-contact-info-email-kv ::= "\"email\"" space ":" space string
        victim-info-contact-info-emergency-contact ::= "{" space victim-info-contact-info-emergency-contact-name-kv "," space victim-info-contact-info-emergency-contact-relationship-kv "," space victim-info-contact-info-emergency-contact-phone-kv "}" space
        victim-info-contact-info-emergency-contact-name-kv ::= "\"name\"" space ":" space string
        victim-info-contact-info-emergency-contact-relationship-kv ::= "\"relationship\"" space ":" space string
        victim-info-contact-info-emergency-contact-phone-kv ::= "\"phone\"" space ":" space string
        victim-info-contact-info-emergency-contact-kv ::= "\"emergency_contact\"" space ":" space victim-info-contact-info-emergency-contact
        victim-info-contact-info-kv ::= "\"contact_info\"" space ":" space victim-info-contact-info
        victim-info-contact-info-rest ::= ( "," space victim-info-resources-kv )? victim-info-resources-rest
        
        # Resources
        victim-info-resources ::= "{" space victim-info-resources-food-status-kv "," space victim-info-resources-water-status-kv "," space victim-info-resources-shelter-status-kv "," space victim-info-resources-communication-devices-kv "}" space
        victim-info-resources-food-status ::= ("\"none\"" | "\"limited\"" | "\"adequate\"" | "\"unknown\"") space
        victim-info-resources-food-status-kv ::= "\"food_status\"" space ":" space victim-info-resources-food-status
        victim-info-resources-water-status ::= ("\"none\"" | "\"limited\"" | "\"adequate\"" | "\"unknown\"") space
        victim-info-resources-water-status-kv ::= "\"water_status\"" space ":" space victim-info-resources-water-status
        victim-info-resources-shelter-status ::= ("\"none\"" | "\"inadequate\"" | "\"adequate\"" | "\"unknown\"") space
        victim-info-resources-shelter-status-kv ::= "\"shelter_status\"" space ":" space victim-info-resources-shelter-status
        victim-info-resources-communication-devices ::= "[" space (string ("," space string)*)? "]" space
        victim-info-resources-communication-devices-kv ::= "\"communication_devices\"" space ":" space victim-info-resources-communication-devices
        victim-info-resources-kv ::= "\"resources\"" space ":" space victim-info-resources
        victim-info-resources-rest ::= ( "," space victim-info-rescue-info-kv )? victim-info-rescue-info-rest
        
        # Rescue Info
        victim-info-rescue-info ::= "{" space victim-info-rescue-info-last-contact-kv "," space victim-info-rescue-info-rescue-team-eta-kv "," space victim-info-rescue-info-special-rescue-needs-kv "}" space
        victim-info-rescue-info-last-contact ::= date-time-string
        victim-info-rescue-info-last-contact-kv ::= "\"last_contact\"" space ":" space victim-info-rescue-info-last-contact
        victim-info-rescue-info-rescue-team-eta-kv ::= "\"rescue_team_eta\"" space ":" space string
        victim-info-rescue-info-special-rescue-needs-kv ::= "\"special_rescue_needs\"" space ":" space string
        victim-info-rescue-info-kv ::= "\"rescue_info\"" space ":" space victim-info-rescue-info
        victim-info-rescue-info-rest ::= ( "," space victim-info-environmental-data-kv )? victim-info-environmental-data-rest
        
        # Environmental Data
        victim-info-environmental-data ::= "{" space victim-info-environmental-data-temperature-kv "," space victim-info-environmental-data-humidity-kv "," space victim-info-environmental-data-air-quality-kv "," space victim-info-environmental-data-weather-kv "}" space
        victim-info-environmental-data-temperature-kv ::= "\"temperature\"" space ":" space number
        victim-info-environmental-data-humidity-kv ::= "\"humidity\"" space ":" space number
        victim-info-environmental-data-air-quality-kv ::= "\"air_quality\"" space ":" space string
        victim-info-environmental-data-weather-kv ::= "\"weather\"" space ":" space string
        victim-info-environmental-data-kv ::= "\"environmental_data\"" space ":" space victim-info-environmental-data
        victim-info-environmental-data-rest ::= ( "," space victim-info-device-data-kv )? victim-info-device-data-rest
        
        # Device Data
        victim-info-device-data ::= "{" space victim-info-device-data-battery-level-kv "," space victim-info-device-data-network-status-kv "}" space
        victim-info-device-data-battery-level-kv ::= "\"battery_level\"" space ":" space number
        victim-info-device-data-network-status ::= ("\"none\"" | "\"weak\"" | "\"strong\"" | "\"unknown\"") space
        victim-info-device-data-network-status-kv ::= "\"network_status\"" space ":" space victim-info-device-data-network-status
        victim-info-device-data-kv ::= "\"device_data\"" space ":" space victim-info-device-data
        victim-info-device-data-rest ::= ( "," space victim-info-social-info-kv )? victim-info-social-info-rest
        
        # Social Info
        victim-info-social-info ::= "{" space victim-info-social-info-group-size-kv "," space victim-info-social-info-dependents-kv "," space victim-info-social-info-nearby-victims-count-kv "," space victim-info-social-info-can-communicate-verbally-kv "}" space
        victim-info-social-info-group-size ::= ([0] | [1-9] [0-9]{0,15}) space
        victim-info-social-info-group-size-kv ::= "\"group_size\"" space ":" space victim-info-social-info-group-size
        victim-info-social-info-dependents ::= ([0] | [1-9] [0-9]{0,15}) space
        victim-info-social-info-dependents-kv ::= "\"dependents\"" space ":" space victim-info-social-info-dependents
        victim-info-social-info-nearby-victims-count ::= ([0] | [1-9] [0-9]{0,15}) space
        victim-info-social-info-nearby-victims-count-kv ::= "\"nearby_victims_count\"" space ":" space victim-info-social-info-nearby-victims-count
        victim-info-social-info-can-communicate-verbally-kv ::= "\"can_communicate_verbally\"" space ":" space boolean
        victim-info-social-info-kv ::= "\"social_info\"" space ":" space victim-info-social-info
        victim-info-social-info-rest ::= ( "," space victim-info-psychological-status-kv )?
        
        # Psychological Status
        victim-info-psychological-status ::= "{" space victim-info-psychological-status-stress-level-kv "," space victim-info-psychological-status-special-needs-kv "}" space
        victim-info-psychological-status-stress-level ::= ("\"low\"" | "\"moderate\"" | "\"high\"" | "\"severe\"" | "\"unknown\"") space
        victim-info-psychological-status-stress-level-kv ::= "\"stress_level\"" space ":" space victim-info-psychological-status-stress-level
        victim-info-psychological-status-special-needs-kv ::= "\"special_needs\"" space ":" space string
        victim-info-psychological-status-kv ::= "\"psychological_status\"" space ":" space victim-info-psychological-status
    """.trimIndent()

    /**
     * List Grammar - Array of strings
     * Use for command outputs, search results, recommendations
     */
    val LIST_GRAMMAR = """
        root = "[" ws (string ("," ws string)*)? ws "]" ws
        string = "\"" (
            [^"\\] |
            "\\" (["\\/bfnrt] | "u" [0-9a-fA-F] [0-9a-fA-F] [0-9a-fA-F] [0-9a-fA-F])
        )* "\"" ws
        ws = ([ \t\n] ws)?
    """.trimIndent()

    /**
     * Key-Value Pairs Grammar
     * Use for simple key-value responses
     */
    val KEY_VALUE_GRAMMAR = """
        root = "{" ws (pair ("," ws pair)*)? "}" ws
        pair = string ":" ws (string | number | boolean) ws
        string = "\"" ([^"\\] | "\\" ["\\/bfnrt])* "\"" ws
        number = ("-"? [0-9]+) ("." [0-9]+)? ws
        boolean = ("true" | "false") ws
        ws = ([ \t\n] ws)?
    """.trimIndent()

    /**
     * Get grammar by name for user-friendly selection
     */
    fun getGrammarByName(name: String): String? = when (name.lowercase()) {
        "json" -> JSON_GRAMMAR
        "response" -> SAFEGUARDIAN_RESPONSE_GRAMMAR
        "emergency" -> EMERGENCY_SURVIVAL_GRAMMAR
        "list" -> LIST_GRAMMAR
        "keyvalue" -> KEY_VALUE_GRAMMAR
        else -> null
    }

    /**
     * List of available grammar templates
     */
    val AVAILABLE_GRAMMARS = listOf(
        "JSON" to "Generic JSON (any structure)",
        "RESPONSE" to "SafeGuardian Response (type, content, confidence)",
        "EMERGENCY" to "Emergency Survival (comprehensive victim info)",
        "LIST" to "Array of strings (commands, results)",
        "KEYVALUE" to "Key-value pairs (simple responses)"
    )
}
