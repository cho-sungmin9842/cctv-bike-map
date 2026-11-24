package com.example.navermaptoapi

import retrofit2.Call
import retrofit2.http.GET
import retrofit2.http.Query

interface ItsCCTVService {
    @GET("cctvInfo")
    fun getCCTVInfo(
        @Query("apiKey") apiKey: String,
        @Query("type") type: String,
        @Query("cctvType") cctvType: String,
        @Query("minX") minX: Double,
        @Query("maxX") maxX: Double,
        @Query("minY") minY: Double,
        @Query("maxY") maxY: Double,
        @Query("getType") getType: String
    ): Call<ItsCCTVResponseEntity>
}