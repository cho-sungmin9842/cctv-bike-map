package com.example.navermaptoapi

import retrofit2.Call
import retrofit2.http.GET
import retrofit2.http.Query

interface JeJuCCTVService {
    @GET("getSnowfallCctvList")
    fun getCCTVInfo(
        @Query("serviceKey") serviceKey: String,
    ): Call<JeJuCCTVResponseEntity>
}