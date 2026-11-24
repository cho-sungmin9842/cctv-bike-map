package com.example.navermaptoapi

import com.google.gson.GsonBuilder
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

class ItsCCTVInfo {
    companion object {
        private const val BASE_URL = "https://openapi.its.go.kr:9443/"
    }

    // gson 객체 생성 및 설정
    val gson = GsonBuilder().setLenient().create()

    // retrofit 객체 생성 및 설정
    val retrofit = Retrofit.Builder()
        .baseUrl(BASE_URL)
        .addConverterFactory(GsonConverterFactory.create(gson))
        .build()
}