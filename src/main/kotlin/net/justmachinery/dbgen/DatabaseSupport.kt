package net.justmachinery.dbgen

data class SelectOperation<Result>(val sql : String, val parameters : Map<String,Any?>)
data class InsertOperation<Result>(val sql : String, val parameters : Map<String,Any?>)
data class DeleteOperation<Result>(val sql : String, val parameters : Map<String,Any?>)
data class UpdateOperation<Result>(val sql : String, val parameters : Map<String,Any?>)