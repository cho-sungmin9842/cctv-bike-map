package com.example.navermaptoapi

import retrofit2.Call
import retrofit2.http.GET
import retrofit2.http.Path

interface SeoulAPIService {
    // 서울시 공공자전거(따릉이) 정보
    @GET("{KEY}/{TYPE}/{SERVICE}/{START_INDEX}/{END_INDEX}")
    fun gettbCycleStationInfo(
        @Path("KEY") key: String,
        @Path("TYPE") type: String,
        @Path("SERVICE") service: String,
        @Path("START_INDEX") startIndex: Int,
        @Path("END_INDEX") endIndex: Int
    ): Call<PublicSeoulBicycleEntity>
}