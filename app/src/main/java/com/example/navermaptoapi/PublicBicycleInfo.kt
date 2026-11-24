package com.example.navermaptoapi

import com.google.gson.GsonBuilder
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

class PublicBicycleInfo {
    companion object {
        private const val BASE_URL = "http://openapi.seoul.go.kr:8088/"
    }
    // gson 객체 생성 및 설정
    val gson = GsonBuilder().setLenient().create()

    // retrofit 객체 생성 및 설정
    val retrofit = Retrofit.Builder()
        .baseUrl(BASE_URL)
        .addConverterFactory(GsonConverterFactory.create(gson))
        .build()
}