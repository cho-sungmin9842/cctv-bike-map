package com.example.navermaptoapi

data class ItsCCTVResponseEntity(
    val response: ItsResponse
)

data class ItsResponse(
    val coordtype: Int,
    val data: List<Data>,
    val datacount: Int
)

data class Data(
    val cctvformat: String,
    val cctvname: String,
    val cctvresolution: String,
    val cctvtype: Int,
    val cctvurl: String,
    val coordx: Double,
    val coordy: Double,
    val filecreatetime: String,
    val roadsectionid: String
)
