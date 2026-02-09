package com.bitchat.android.ai

import android.util.Log
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Converts JSON schemas to GBNF grammar rules
 * 
 * This allows users to paste their own JSON structures and automatically
 * generate GBNF rules for constrained output.
 */
object JSONToGBNFConverter {
    
    private const val TAG = "JSONToGBNFConverter"
    
    /**
     * Result class for conversion operations
     */
    sealed class ConversionResult {
        data class Success(val grammar: String, val schema: JSONObject) : ConversionResult()
        data class Error(val message: String) : ConversionResult()
    }
    
    /**
     * Convert a JSON schema string to GBNF grammar
     * 
     * @param jsonString Raw JSON string (can be from user paste)
     * @return ConversionResult with grammar or error message
     */
    fun convertJsonSchemaToGBNF(jsonString: String): ConversionResult {
        return try {
            val jsonSchema = JSONObject(jsonString.trim())
            val grammar = buildGrammarFromSchema(jsonSchema)
            ConversionResult.Success(grammar, jsonSchema)
        } catch (e: JSONException) {
            Log.e(TAG, "Invalid JSON schema", e)
            ConversionResult.Error("Invalid JSON: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Conversion error", e)
            ConversionResult.Error("Conversion failed: ${e.message}")
        }
    }
    
    /**
     * Build GBNF grammar from a JSON schema
     */
    private fun buildGrammarFromSchema(schema: JSONObject): String {
        val rules = mutableMapOf<String, String>()
        val rootRule = generateRule(schema, "root", rules)
        
        val sb = StringBuilder()
        
        // Root rule
        sb.append("root = ").append(rootRule).append("\n\n")
        
        // Additional rules - sort by key name for consistent output
        for ((name, rule) in rules.entries.sortedBy { it.key }) {
            sb.append("$name = $rule\n")
        }
        
        // Basic definitions
        sb.append("""
            
            # Basic types
            string = "\"" ([^"\\] | "\\" (["\\/bfnrt] | "u" [0-9a-fA-F] [0-9a-fA-F] [0-9a-fA-F] [0-9a-fA-F]))* "\""
            number = ("-"? ([0-9] | [1-9] [0-9]*)) ("." [0-9]+)? ([eE] ("+"|"-")? [0-9]+)?
            boolean = "true" | "false"
            null_value = "null"
            integer = ("-"? [0-9]+)
            ws = ([ \t\n] ws)?
        """.trimIndent())
        
        return sb.toString()
    }
    
    /**
     * Generate a GBNF rule for a JSON value
     */
    private fun generateRule(
        schema: Any,
        name: String,
        rules: MutableMap<String, String>
    ): String {
        return when (schema) {
            is JSONObject -> generateObjectRule(schema, name, rules)
            is JSONArray -> generateArrayRule(schema, name, rules)
            is String -> "string"
            is Number -> "number"
            is Boolean -> "boolean"
            null -> "null_value"
            else -> "string"
        }
    }
    
    /**
     * Generate rule for JSON object
     */
    private fun generateObjectRule(
        obj: JSONObject,
        name: String,
        rules: MutableMap<String, String>
    ): String {
        val keys = obj.keys()
        if (!keys.hasNext()) {
            return "\"{\" ws \"}\""
        }
        
        val properties = mutableListOf<String>()
        val keyList = keys.asSequence().toList()
        
        for ((index, key) in keyList.withIndex()) {
            val value = obj.get(key)
            val fieldRule = generateRule(value, "${name}_${key}", rules)
            val fieldDef = "\"\\\"$key\\\"\" ws \":\" ws $fieldRule ws"
            properties.add(fieldDef)
        }
        
        val propertiesStr = properties.joinToString(" \",\" ws ", postfix = " ")
        return "\"{\" ws $propertiesStr \"}\""
    }
    
    /**
     * Generate rule for JSON array
     */
    private fun generateArrayRule(
        arr: JSONArray,
        name: String,
        rules: MutableMap<String, String>
    ): String {
        if (arr.length() == 0) {
            return "\"[\" ws \"]\""
        }
        
        // Get first element as template
        val firstElement = arr.get(0)
        val elementRule = generateRule(firstElement, "${name}_item", rules)
        
        return "\"[\" ws ($elementRule (ws \",\" ws $elementRule)*)? ws \"]\""
    }
    
    /**
     * Validate JSON string
     */
    fun validateJSON(jsonString: String): Boolean {
        return try {
            JSONObject(jsonString.trim())
            true
        } catch (e: JSONException) {
            false
        }
    }
    
    /**
     * Extract JSON schema from example JSON
     * (Automatically infers types)
     */
    fun inferSchema(jsonString: String): ConversionResult {
        return try {
            val json = JSONObject(jsonString.trim())
            
            // Create schema with inferred types
            val schema = JSONObject()
            val keys = json.keys()
            
            for (key in keys) {
                val value = json.get(key)
                val type = when (value) {
                    is String -> "string"
                    is Number -> "number"
                    is Boolean -> "boolean"
                    is JSONObject -> "object"
                    is JSONArray -> "array"
                    else -> "string"
                }
                schema.put(key, JSONObject().put("type", type))
            }
            
            ConversionResult.Success(schema.toString(2), schema)
        } catch (e: Exception) {
            ConversionResult.Error("Schema inference failed: ${e.message}")
        }
    }
    
    /**
     * Parse and beautify JSON
     */
    fun formatJSON(jsonString: String): ConversionResult {
        return try {
            val json = JSONObject(jsonString.trim())
            val formatted = json.toString(2)
            ConversionResult.Success(formatted, json)
        } catch (e: Exception) {
            ConversionResult.Error("Invalid JSON: ${e.message}")
        }
    }
    
    /**
     * Generate GBNF from example JSON (simple mode)
     */
    fun generateSimpleGrammar(jsonString: String): ConversionResult {
        return try {
            val json = JSONObject(jsonString.trim())
            
            // Build simple grammar that matches the structure
            val grammar = buildSimpleGrammar(json)
            ConversionResult.Success(grammar, json)
        } catch (e: Exception) {
            ConversionResult.Error("Grammar generation failed: ${e.message}")
        }
    }
    
    /**
     * Build simplified GBNF grammar from JSON
     */
    private fun buildSimpleGrammar(json: JSONObject): String {
        val keys = json.keys().asSequence().toList()
        
        val properties = keys.map { key ->
            "\"\\\"$key\\\"\" ws \":\" ws value ws"
        }.joinToString(" \",\" ws ", postfix = " ")
        
        return """
            root = "{" ws $properties "}"
            value = string | number | boolean | null_value
            string = "\"" ([^"\\] | "\\" ["\\/bfnrt])* "\""
            number = ("-"? [0-9]+) ("." [0-9]+)?
            boolean = "true" | "false"
            null_value = "null"
            ws = ([ \t\n] ws)?
        """.trimIndent()
    }
}
