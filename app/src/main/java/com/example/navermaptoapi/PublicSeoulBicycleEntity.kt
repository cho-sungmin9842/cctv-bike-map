package com.example.navermaptoapi

import com.google.gson.annotations.SerializedName

data class PublicSeoulBicycleEntity(
    @SerializedName("rentBikeStatus")
    val rentBikeStatus: RentBikeStatus
)

data class RentBikeStatus(
    @SerializedName("RESULT")
    val result: Result,
    @SerializedName("list_total_count")
    val listTotalCount: Int,
    @SerializedName("row")
    val row: List<Row>
)

data class Result(
    @SerializedName("CODE")
    val code: String,
    @SerializedName("MESSAGE")
    val message: String
)

data class Row(
    @SerializedName("parkingBikeTotCnt")
    val parkingBikeTotCnt: String,
    @SerializedName("rackTotCnt")
    val rackTotCnt: String,
    @SerializedName("shared")
    val shared: String,
    @SerializedName("stationId")
    val stationId: String,
    @SerializedName("stationLatitude")
    val stationLatitude: String,
    @SerializedName("stationLongitude")
    val stationLongitude: String,
    @SerializedName("stationName")
    val stationName: String
)
