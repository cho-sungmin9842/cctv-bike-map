package com.example.navermaptoapi

data class JeJuCCTVResponseEntity(
    val response: JeJuResponse
)

data class JeJuResponse(
    val body: Body,
    val header: Header
)

data class Body(
    val items: Items,
    val numOfRows: Int,
    val pageNo: Int,
    val totalCount: Int
)

data class Header(
    val resultCode: String,
    val resultMsg: String
)

data class Items(
    val item: List<Item>
)

data class Item(
    val cctvUrl: String,
    val dataCd: String,
    val laCrdnt: String,
    val loCrdnt: String,
    val spotNm: String,
    val spotSe: String,
    val useYn: String
)