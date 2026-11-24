package com.example.navermaptoapi

data class ExcelData(
    var num: Long,
    var id: String,
    var CCTVName: String,
    var centerName: String,
    var xCoord: Double,
    var yCoord: Double,
    var CCTVUrl: String? = null
)
