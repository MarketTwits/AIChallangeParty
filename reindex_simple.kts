#!/usr/bin/env kotlin

import java.sql.DriverManager

// Simple reindex script without dependencies
println("=== Simple Reindex Test ===")

val dbPath = "data/documents.db"
val connection = DriverManager.getConnection("jdbc:sqlite:$dbPath")

// Check table schema
val stmt = connection.createStatement()
val rs = stmt.executeQuery("PRAGMA table_info(documents)")

println("Current table schema:")
while (rs.next()) {
    println("  ${rs.getString("name")} - ${rs.getString("type")}")
}

// Clear database
stmt.executeUpdate("DELETE FROM documents")
println("Database cleared")

connection.close()
println("Done!")
