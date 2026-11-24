package com.example.navermaptoapi

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import com.example.navermaptoapi.databinding.ActivityMainBinding
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.MarkerOptions
import com.naver.maps.geometry.LatLng
import com.naver.maps.map.CameraAnimation
import com.naver.maps.map.CameraUpdate
import com.naver.maps.map.LocationTrackingMode
import com.naver.maps.map.NaverMap
import com.naver.maps.map.OnMapReadyCallback
import com.naver.maps.map.overlay.InfoWindow
import com.naver.maps.map.overlay.Marker
import com.naver.maps.map.overlay.Overlay
import com.naver.maps.map.overlay.OverlayImage
import com.naver.maps.map.util.FusedLocationSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.apache.poi.poifs.filesystem.POIFSFileSystem
import org.apache.poi.ss.usermodel.Cell
import org.apache.poi.ss.usermodel.CellValue
import org.apache.poi.ss.usermodel.WorkbookFactory
import org.apache.poi.xssf.usermodel.XSSFCell
import org.apache.poi.xssf.usermodel.XSSFRow
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.io.FileInputStream
import java.io.InputStream
import java.util.stream.Stream
import kotlin.streams.toList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity(), OnMapReadyCallback {
    companion object {
        private const val LOCATION_PERMISSION_REQUEST_CODE = 1000
        private const val HEIGHT = 50
        private const val CCTV_Z_INDEX = 300000
        private const val WIDTH = 50
        private const val BIKE_ICON_SIZE = 64
        private const val MARKER_BATCH_SIZE = 100
    }

    private val binding by lazy {
        ActivityMainBinding.inflate(layoutInflater)
    }
    private lateinit var locationSource: FusedLocationSource
    // 따릉이 대여소 마커 아이콘 (벡터 드로어블을 비트맵으로 변환)
    private val bikeIcon by lazy {
        val drawable = ContextCompat.getDrawable(this, R.drawable.ic_bike)!!
        OverlayImage.fromBitmap(drawable.toBitmap(BIKE_ICON_SIZE, BIKE_ICON_SIZE))
    }
    
    private val icon by lazy {
        // 1300x1300 원본이 화면 밀도에 맞춰 자동 확대되면 GPU 텍스처 한계를 넘어 마커가 그려지지 않으므로
        // 밀도 스케일링을 끄고 축소해서 디코딩한다.
        val options = BitmapFactory.Options().apply {
            inScaled = false
            inSampleSize = 8
        }
        val b = BitmapFactory.decodeResource(resources, R.drawable.cctv, options)
        val bitmap = getCroppedBitmap(b)
        OverlayImage.fromBitmap(bitmap)
    }

    // 지도에 그릴 마커 리스트
    private var markers = mutableListOf<Marker>()

    // 마커 하나를 그리는 데 필요한 가벼운 데이터 (실제 Marker 는 나눠서 생성)
    private class MarkerSpec(
        val position: LatLng,
        val caption: String,
        val infoText: String,
        val videoUrl: String?,
        val isBike: Boolean
    )

    // 마커의 tag 에 담아 두는 정보창용 데이터
    private class MarkerTag(val infoText: String, val videoUrl: String?)

    private val mainHandler = Handler(Looper.getMainLooper())

    // 엑셀 파싱처럼 오래 걸리는 작업을 메인 스레드 밖에서 처리하기 위한 실행기
    private val ioExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    // 모든 마커가 공유하는 정보창.
    // 마커마다 InfoWindow 를 만들면 수만 개 객체가 생성되어 시작이 느려지므로 하나만 재사용한다.
    private val sharedInfoWindow by lazy {
        InfoWindow().apply {
            adapter = object : InfoWindow.DefaultTextAdapter(this@MainActivity) {
                override fun getText(infoWindow: InfoWindow): CharSequence =
                    (infoWindow.marker?.tag as? MarkerTag)?.infoText.orEmpty()
            }
            // 정보창을 누르면 CCTV 영상 화면으로 이동 (영상 URL 이 없는 따릉이 마커는 무시)
            setOnClickListener {
                val url = (marker?.tag as? MarkerTag)?.videoUrl
                if (!url.isNullOrEmpty()) {
                    startActivity(
                        Intent(this@MainActivity, VideoActivity::class.java).putExtra("url", url)
                    )
                }
                false
            }
        }
    }

    // 마커 클릭: 정보창이 닫혀 있으면 해당 마커로 이동해 열고, 열려 있으면 닫음
    private val markerClickListener = Overlay.OnClickListener { overlay ->
        val marker = overlay as Marker
        if (marker.infoWindow == null) {
            moveCamera(marker.position)
            sharedInfoWindow.open(marker)
        } else {
            sharedInfoWindow.close()
        }
        true
    }

    private lateinit var naverMap: NaverMap
    private lateinit var lifecycleState: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        binding.mapView.onCreate(savedInstanceState)
        binding.mapView.getMapAsync(this)
        lifecycleState = lifecycle.currentState.toString()
        locationSource = FusedLocationSource(this, LOCATION_PERMISSION_REQUEST_CODE)
        if (ActivityCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        //위치 권한이 주어지지 않은 경우
        else {
            showDialog()
        }
    }

    override fun onStart() {
        super.onStart()
        binding.mapView.onStart()
    }

    override fun onResume() {
        super.onResume()
        if (lifecycleState == "CREATED") {
            //위치 권한이 이미 주어진 경우
            if (ActivityCompat.checkSelfPermission(
                    this, Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(
                    this, Manifest.permission.ACCESS_COARSE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
            //위치 권한이 주어지지 않은 경우
            else {
                showDialog()
            }
        }
        binding.mapView.onResume()

    }

    private fun getDaejeonCCTVData() {
        val latLngList = ArrayList<LatLng>()
        val cctvNameList = ArrayList<String>()
        val cctvUrlList = ArrayList<String>()
        latLngList.add(LatLng(36.30501111, 127.3531778))
        latLngList.add(LatLng(36.32798889, 127.3886056))
        latLngList.add(LatLng(36.331325, 127.3842278))
        latLngList.add(LatLng(36.35861111, 127.3597389))
        latLngList.add(LatLng(36.37464167, 127.3796278))
        latLngList.add(LatLng(36.36416667, 127.3379167))
        latLngList.add(LatLng(36.34482222, 127.40185))
        latLngList.add(LatLng(36.35646667, 127.4053889))
        latLngList.add(LatLng(36.32241944, 127.4229694))
        latLngList.add(LatLng(36.32945, 127.44315))
        latLngList.add(LatLng(36.29596389, 127.4603472))
        latLngList.add(LatLng(36.33125278, 127.4326583))
        latLngList.add(LatLng(36.354242, 127.363899))
        latLngList.add(LatLng(36.33359722, 127.4195472))
        latLngList.add(LatLng(36.37156944, 127.395025))
        latLngList.add(LatLng(36.37811667, 127.4116222))
        latLngList.add(LatLng(36.360679, 127.305576))
        latLngList.add(LatLng(36.388686, 127.378696))
        latLngList.add(LatLng(36.31298333, 127.380125))
        latLngList.add(LatLng(36.35105833, 127.4422111))
        latLngList.add(LatLng(36.4439999, 127.385845))
        latLngList.add(LatLng(36.4386269, 127.394786))
        latLngList.add(LatLng(36.36504444, 127.3796083))
        latLngList.add(LatLng(36.35769444, 127.3902778))
        latLngList.add(LatLng(36.32857778, 127.3813611))
        latLngList.add(LatLng(36.317178, 127.426079))
        latLngList.add(LatLng(36.31308611, 127.43485))
        latLngList.add(LatLng(36.416756, 127.377771))
        latLngList.add(LatLng(36.30918333, 127.3689556))
        latLngList.add(LatLng(36.338175, 127.4296306))
        latLngList.add(LatLng(36.34064167, 127.4211167))
        latLngList.add(LatLng(36.34902222, 127.401725))
        latLngList.add(LatLng(36.32233333, 127.4124611))
        latLngList.add(LatLng(36.35982222, 127.379425))
        latLngList.add(LatLng(36.34071111, 127.436025))
        latLngList.add(LatLng(36.32419444, 127.4203028))
        latLngList.add(LatLng(36.4303769, 127.405463))
        latLngList.add(LatLng(36.35148, 127.38672))
        latLngList.add(LatLng(36.436225, 127.3916278))
        latLngList.add(LatLng(36.450978, 127.428763))
        latLngList.add(LatLng(36.33393333, 127.3719639))
        latLngList.add(LatLng(36.371, 127.3833639))
        latLngList.add(LatLng(36.32865833, 127.4051417))
        latLngList.add(LatLng(36.35875207, 127.4153719))
        latLngList.add(LatLng(36.332188, 127.34809))
        latLngList.add(LatLng(36.3382, 127.3932083))
        latLngList.add(LatLng(36.34737778, 127.4318694))
        latLngList.add(LatLng(36.32670278, 127.4352056))
        latLngList.add(LatLng(36.38019722, 127.4067694))
        latLngList.add(LatLng(36.36697222, 127.3272611))
        latLngList.add(LatLng(36.36443, 127.32712))
        latLngList.add(LatLng(36.35358889, 127.3416222))
        latLngList.add(LatLng(36.38149722, 127.4259778))
        latLngList.add(LatLng(36.32698889, 127.4215833))
        latLngList.add(LatLng(36.35921389, 127.43205))
        latLngList.add(LatLng(36.32873889, 127.4258889))
        latLngList.add(LatLng(36.33674722, 127.4160778))
        latLngList.add(LatLng(36.298908, 127.323606))
        latLngList.add(LatLng(36.318225, 127.4175667))
        latLngList.add(LatLng(36.362328, 127.344702))
        latLngList.add(LatLng(36.384542, 127.32053))
        latLngList.add(LatLng(36.34879722, 127.3768778))
        latLngList.add(LatLng(36.32571111, 127.3948528))
        latLngList.add(LatLng(36.317275, 127.459869))
        latLngList.add(LatLng(36.36465556, 127.3935722))
        latLngList.add(LatLng(36.36311111, 127.39965))
        cctvNameList.add("가수원네거리")
        cctvNameList.add("가장교오거리")
        cctvNameList.add("가장네거리")
        cctvNameList.add("갑천대교네거리")
        cctvNameList.add("과학공원네거리")
        cctvNameList.add("궁동네거리")
        cctvNameList.add("남선공원네거리")
        cctvNameList.add("농수산시장오거리")
        cctvNameList.add("대고오거리")
        cctvNameList.add("대동역오거리")
        cctvNameList.add("대성삼거리")
        cctvNameList.add("대전역네거리")
        cctvNameList.add("대전일보네거리")
        cctvNameList.add("대종로네거리")
        cctvNameList.add("대화JC")
        cctvNameList.add("대화영업소")
        cctvNameList.add("덕명네거리")
        cctvNameList.add("도룡삼거리")
        cctvNameList.add("도마네거리")
        cctvNameList.add("동부네거리")
        cctvNameList.add("둔곡터널앞")
        cctvNameList.add("들말네거리")
        cctvNameList.add("만년네거리")
        cctvNameList.add("모정네거리")
        cctvNameList.add("변동오거리")
        cctvNameList.add("보문산공원오거리")
        cctvNameList.add("부사오거리")
        cctvNameList.add("북대전IC네거리")
        cctvNameList.add("불티구름다리")
        cctvNameList.add("삼성네거리")
        cctvNameList.add("삼성동성당네거리")
        cctvNameList.add("삼천교네거리")
        cctvNameList.add("서대전네거리")
        cctvNameList.add("선사유적네거리")
        cctvNameList.add("성남네거리")
        cctvNameList.add("성모병원오거리")
        cctvNameList.add("시알들네거리")
        cctvNameList.add("시청역네거리")
        cctvNameList.add("신구교네거리")
        cctvNameList.add("신탄진네거리")
        cctvNameList.add("안골네거리")
        cctvNameList.add("엑스포지하차도")
        cctvNameList.add("오룡역네거리")
        cctvNameList.add("오정네거리")
        cctvNameList.add("옥녀봉네거리")
        cctvNameList.add("용문역네거리")
        cctvNameList.add("용전네거리")
        cctvNameList.add("원동네거리")
        cctvNameList.add("원촌네거리")
        cctvNameList.add("월드컵경기장네거리")
        cctvNameList.add("유성IC삼거리")
        cctvNameList.add("유성네거리")
        cctvNameList.add("읍네삼거리")
        cctvNameList.add("중구청네거리")
        cctvNameList.add("중리네거리")
        cctvNameList.add("중앙로역네거리")
        cctvNameList.add("중촌네거리")
        cctvNameList.add("진잠네거리")
        cctvNameList.add("충대병원네거리")
        cctvNameList.add("충대오거리")
        cctvNameList.add("침신대네거리")
        cctvNameList.add("큰마을네거리")
        cctvNameList.add("태평오거리")
        cctvNameList.add("판암1동주민센터네거리")
        cctvNameList.add("평송수련원삼거리")
        cctvNameList.add("한밭영업소")
        cctvUrlList.add("https://cctvsecn01.ktict.co.kr:8082/koroad01403/L300011/playlist.m3u8?wmsAuthSign=c2VydmVyX3RpbWU9MTAvMjQvMjAyMyAxOjAwOjIzIFBNJmhhc2hfdmFsdWU9WjZ4ZVhCU3VrN2RSbW9pQnJZTWp0UT09JnZhbGlkbWludXRlcz0xMjA=")
        cctvUrlList.add("https://cctvsecn01.ktict.co.kr:8082/koroad01203/L300053/playlist.m3u8?wmsAuthSign=c2VydmVyX3RpbWU9MTAvMjQvMjAyMyAyOjM0OjAzIFBNJmhhc2hfdmFsdWU9cWhodUMvc1k0T2c5TzlVajlJalNmQT09JnZhbGlkbWludXRlcz0xMjA=")
        cctvUrlList.add("https://cctvsecn01.ktict.co.kr:8082/koroad01206/L300056/playlist.m3u8?wmsAuthSign=c2VydmVyX3RpbWU9MTAvMjQvMjAyMyAyOjM4OjQ4IFBNJmhhc2hfdmFsdWU9blNqTy9zb2J1OTh6bG1HdytaKytYQT09JnZhbGlkbWludXRlcz0xMjA=")
        cctvUrlList.add("https://cctvsecn01.ktict.co.kr:8082/koroad01102/L300017/playlist.m3u8?wmsAuthSign=c2VydmVyX3RpbWU9MTAvMjQvMjAyMyAyOjQwOjQ0IFBNJmhhc2hfdmFsdWU9aTRmMXBhZ29JeGNVaklkeWg4M2gvQT09JnZhbGlkbWludXRlcz0xMjA=")
        cctvUrlList.add("https://cctvsecn01.ktict.co.kr:8082/koroad01206/L300028/playlist.m3u8?wmsAuthSign=c2VydmVyX3RpbWU9MTAvMjQvMjAyMyAyOjQ1OjUzIFBNJmhhc2hfdmFsdWU9eHd5RXVaZEt6TE9CMzE5bitkVmxFUT09JnZhbGlkbWludXRlcz0xMjA=")
        cctvUrlList.add("https://cctvsecn01.ktict.co.kr:8082/koroad01101/L300016/playlist.m3u8?wmsAuthSign=c2VydmVyX3RpbWU9MTAvMjQvMjAyMyAyOjU3OjE1IFBNJmhhc2hfdmFsdWU9N0JaYTBYUDVsSjJRVXppdEhlbXRPQT09JnZhbGlkbWludXRlcz0xMjA=")
        cctvUrlList.add("https://cctvsecn01.ktict.co.kr:8082/koroad01302/L300003/playlist.m3u8?wmsAuthSign=c2VydmVyX3RpbWU9MTAvMjQvMjAyMyAyOjU5OjQxIFBNJmhhc2hfdmFsdWU9aGlOeW9jL2lVMXYyMnduVUcwTm4xQT09JnZhbGlkbWludXRlcz0xMjA=")
        cctvUrlList.add("https://cctvsecn01.ktict.co.kr:8082/koroad01401/L300037/playlist.m3u8?wmsAuthSign=c2VydmVyX3RpbWU9MTAvMjQvMjAyMyAzOjAwOjM1IFBNJmhhc2hfdmFsdWU9WFoyVmpJMkQ1R0FrZmtUU3RjNHg4dz09JnZhbGlkbWludXRlcz0xMjA=")
        cctvUrlList.add("https://cctvsecn01.ktict.co.kr:8082/koroad01401/L300009/playlist.m3u8?wmsAuthSign=c2VydmVyX3RpbWU9MTAvMjQvMjAyMyAzOjAyOjQxIFBNJmhhc2hfdmFsdWU9ZnNOU2QybGZJMGRjei8rVnFxRllYZz09JnZhbGlkbWludXRlcz0xMjA=")
        cctvUrlList.add("https://cctvsecn01.ktict.co.kr:8082/koroad01403/L300039/playlist.m3u8?wmsAuthSign=c2VydmVyX3RpbWU9MTAvMjQvMjAyMyAzOjA0OjQyIFBNJmhhc2hfdmFsdWU9ZEpzSVJrcXBEQU10K3llOXpwR3oyUT09JnZhbGlkbWludXRlcz0xMjA=")
        cctvUrlList.add("https://cctvsecn01.ktict.co.kr:8082/koroad01307/L300008/playlist.m3u8?wmsAuthSign=c2VydmVyX3RpbWU9MTAvMjQvMjAyMyAzOjA2OjM3IFBNJmhhc2hfdmFsdWU9UUFMTDRaRSt0ZGFjZFhHUzZ1ejgyQT09JnZhbGlkbWludXRlcz0xMjA=")
        cctvUrlList.add("https://cctvsecn01.ktict.co.kr:8082/koroad01406/L300042/playlist.m3u8?wmsAuthSign=c2VydmVyX3RpbWU9MTAvMjQvMjAyMyAzOjA3OjU5IFBNJmhhc2hfdmFsdWU9RExXQmxWOVBNbElwN1RNTHRkcnRiZz09JnZhbGlkbWludXRlcz0xMjA=")
        cctvUrlList.add("https://cctvsecn01.ktict.co.kr:8082/koroad01403/L300069/playlist.m3u8?wmsAuthSign=c2VydmVyX3RpbWU9MTAvMjQvMjAyMyAzOjEwOjM3IFBNJmhhc2hfdmFsdWU9TzhTRlBhVHkwMitkL25lcDJLNXZpQT09JnZhbGlkbWludXRlcz0xMjA=")
        cctvUrlList.add("https://cctvsecn01.ktict.co.kr:8082/koroad01203/L300025/playlist.m3u8?wmsAuthSign=c2VydmVyX3RpbWU9MTAvMjQvMjAyMyAzOjExOjQxIFBNJmhhc2hfdmFsdWU9NmtJUW1SRXdaOUIyTE5yWGFZUVQzZz09JnZhbGlkbWludXRlcz0xMjA=")
        cctvUrlList.add("https://cctvsecn01.ktict.co.kr:8082/koroad01303/L300032/playlist.m3u8?wmsAuthSign=c2VydmVyX3RpbWU9MTAvMjQvMjAyMyAzOjEzOjIyIFBNJmhhc2hfdmFsdWU9QlJQb2xSVHZTZE4xbXZKa3Z1V1RTQT09JnZhbGlkbWludXRlcz0xMjA=")
        cctvUrlList.add("https://cctvsecn01.ktict.co.kr:8082/koroad01304/L300033/playlist.m3u8?wmsAuthSign=c2VydmVyX3RpbWU9MTAvMjQvMjAyMyAzOjE1OjQ1IFBNJmhhc2hfdmFsdWU9Z28wYSt0THV4YVFPL3BFVkZyQTdMdz09JnZhbGlkbWludXRlcz0xMjA=")
        cctvUrlList.add("https://cctvsecn01.ktict.co.kr:8082/koroad01105/L300020/playlist.m3u8?wmsAuthSign=c2VydmVyX3RpbWU9MTAvMjQvMjAyMyAzOjE3OjQzIFBNJmhhc2hfdmFsdWU9WlJHRTN5Sk9tMDFWaC9JNlBhUy9vUT09JnZhbGlkbWludXRlcz0xMjA=")
        cctvUrlList.add("https://cctvsecn01.ktict.co.kr:8082/koroad01304/L300062/playlist.m3u8?wmsAuthSign=c2VydmVyX3RpbWU9MTAvMjQvMjAyMyAzOjE4OjQ2IFBNJmhhc2hfdmFsdWU9bmp5UDZFKzB1N2xDUnNNQ0FKS1R2dz09JnZhbGlkbWludXRlcz0xMjA=")
        cctvUrlList.add("https://cctvsecn01.ktict.co.kr:8082/koroad01404/L300012/playlist.m3u8?wmsAuthSign=c2VydmVyX3RpbWU9MTAvMjQvMjAyMyAzOjIwOjA4IFBNJmhhc2hfdmFsdWU9Ym5tS3pvcXF5U0lLR05oVHlxV1JMUT09JnZhbGlkbWludXRlcz0xMjA=")
        cctvUrlList.add("https://cctvsecn01.ktict.co.kr:8082/koroad01402/L300038/playlist.m3u8?wmsAuthSign=c2VydmVyX3RpbWU9MTAvMjQvMjAyMyAzOjI4OjIwIFBNJmhhc2hfdmFsdWU9YVhka3BaR3QyYTlWa0RnUFJmY1dkUT09JnZhbGlkbWludXRlcz0xMjA=")
        cctvUrlList.add("https://cctvsecn01.ktict.co.kr:8082/koroad01303/L300061/playlist.m3u8?wmsAuthSign=c2VydmVyX3RpbWU9MTAvMjQvMjAyMyAzOjMwOjA2IFBNJmhhc2hfdmFsdWU9R2dRS3lIYVoyVE16MUV1VHdhUUNJUT09JnZhbGlkbWludXRlcz0xMjA=")
        cctvUrlList.add("https://cctvsecn01.ktict.co.kr:8082/koroad01306/L300064/playlist.m3u8?wmsAuthSign=c2VydmVyX3RpbWU9MTAvMjQvMjAyMyAzOjMxOjIzIFBNJmhhc2hfdmFsdWU9WUVGVktlYWpEYXZmN3h1YzBTV05zdz09JnZhbGlkbWludXRlcz0xMjA=")
        cctvUrlList.add("https://cctvsecn01.ktict.co.kr:8082/koroad01205/L300027/playlist.m3u8?wmsAuthSign=c2VydmVyX3RpbWU9MTAvMjQvMjAyMyAzOjM0OjE0IFBNJmhhc2hfdmFsdWU9a2NMSVczWmJmdTFFc0Q4UWpGcUI2UT09JnZhbGlkbWludXRlcz0xMjA=")
        cctvUrlList.add("https://cctvsecn01.ktict.co.kr:8082/koroad01301/L300030/playlist.m3u8?wmsAuthSign=c2VydmVyX3RpbWU9MTAvMjQvMjAyMyAzOjM1OjE1IFBNJmhhc2hfdmFsdWU9WDZIQnFHQWJGU1NPSXhoTlBreEhqQT09JnZhbGlkbWludXRlcz0xMjA=")
        cctvUrlList.add("https://cctvsecn01.ktict.co.kr:8082/koroad01204/L300054/playlist.m3u8?wmsAuthSign=c2VydmVyX3RpbWU9MTAvMjQvMjAyMyAzOjM2OjQ5IFBNJmhhc2hfdmFsdWU9NmFiVWFpY0hGWEI0WE5iU3k3U25CZz09JnZhbGlkbWludXRlcz0xMjA=")
        cctvUrlList.add("https://cctvsecn01.ktict.co.kr:8082/koroad01305/L300063/playlist.m3u8?wmsAuthSign=c2VydmVyX3RpbWU9MTAvMjQvMjAyMyAzOjM4OjQ4IFBNJmhhc2hfdmFsdWU9OTlEYnlQMlFUd2EyeWhLY05JR0s0Zz09JnZhbGlkbWludXRlcz0xMjA=")
        cctvUrlList.add("https://cctvsecn01.ktict.co.kr:8082/koroad01101/L300044/playlist.m3u8?wmsAuthSign=c2VydmVyX3RpbWU9MTAvMjUvMjAyMyAzOjU1OjQ2IEFNJmhhc2hfdmFsdWU9dkthbHV0OWwyK2xIcVhNc0NZNEZ3UT09JnZhbGlkbWludXRlcz0xMjA=")
        cctvUrlList.add("https://cctvsecn01.ktict.co.kr:8082/koroad01202/L300024/playlist.m3u8?wmsAuthSign=c2VydmVyX3RpbWU9MTAvMjUvMjAyMyA0OjAwOjE4IEFNJmhhc2hfdmFsdWU9ZUxmYU5QU0pBWWF6L3g4TUpnOGZ2dz09JnZhbGlkbWludXRlcz0xMjA=")
        cctvUrlList.add("https://cctvsecn01.ktict.co.kr:8082/koroad01402/L300010/playlist.m3u8?wmsAuthSign=c2VydmVyX3RpbWU9MTAvMjUvMjAyMyA0OjAxOjI5IEFNJmhhc2hfdmFsdWU9cWZEM3JFNWo3U1k4NkJhZmZ4aXozdz09JnZhbGlkbWludXRlcz0xMjA=")
        cctvUrlList.add("https://cctvsecn01.ktict.co.kr:8082/koroad01405/L300041/playlist.m3u8?wmsAuthSign=c2VydmVyX3RpbWU9MTAvMjUvMjAyMyA0OjAyOjIxIEFNJmhhc2hfdmFsdWU9TS9aMU5wWjZKL1NCZDdPdUtyOFp4dz09JnZhbGlkbWludXRlcz0xMjA=")
        cctvUrlList.add("https://cctvsecn01.ktict.co.kr:8082/koroad01306/L300007/playlist.m3u8?wmsAuthSign=c2VydmVyX3RpbWU9MTAvMjUvMjAyMyA0OjA0OjU0IEFNJmhhc2hfdmFsdWU9am16eDlnc1lsa0toNmdvUHNMaEVsUT09JnZhbGlkbWludXRlcz0xMjA=")
        cctvUrlList.add("https://cctvsecn01.ktict.co.kr:8082/koroad01204/L300026/playlist.m3u8?wmsAuthSign=c2VydmVyX3RpbWU9MTAvMjUvMjAyMyA0OjA5OjI5IEFNJmhhc2hfdmFsdWU9WGNGWUVodjlBRFhPa2ZSREtEdnQrQT09JnZhbGlkbWludXRlcz0xMjA=")
        cctvUrlList.add("https://cctvsecn01.ktict.co.kr:8082/koroad01106/L300049/playlist.m3u8?wmsAuthSign=c2VydmVyX3RpbWU9MTAvMjUvMjAyMyA1OjA5OjI3IEFNJmhhc2hfdmFsdWU9M3lOd0FwUk9ONXd2b0h4RTdLbGx3UT09JnZhbGlkbWludXRlcz0xMjA=")
        cctvUrlList.add("https://cctvsecn01.ktict.co.kr:8082/koroad01303/L300004/playlist.m3u8?wmsAuthSign=c2VydmVyX3RpbWU9MTAvMjUvMjAyMyA1OjExOjAxIEFNJmhhc2hfdmFsdWU9WTFEbHFNM21ZclRwVmwwVHI1ckcyZz09JnZhbGlkbWludXRlcz0xMjA=")
        cctvUrlList.add("https://cctvsecn01.ktict.co.kr:8082/koroad01404/L300040/playlist.m3u8?wmsAuthSign=c2VydmVyX3RpbWU9MTAvMjUvMjAyMyA1OjEyOjAxIEFNJmhhc2hfdmFsdWU9TlNTdGRJOU5qNi81ank0V2tMZ0J2UT09JnZhbGlkbWludXRlcz0xMjA=")
        cctvUrlList.add("https://cctvsecn01.ktict.co.kr:8082/koroad01104/L300047/playlist.m3u8?wmsAuthSign=c2VydmVyX3RpbWU9MTAvMjUvMjAyMyA1OjE0OjM2IEFNJmhhc2hfdmFsdWU9RW1LTk5mMUU3R0UzY3dMZDJURlRFdz09JnZhbGlkbWludXRlcz0xMjA=")
        cctvUrlList.add("https://cctvsecn01.ktict.co.kr:8082/koroad01105/L300078/playlist.m3u8?wmsAuthSign=c2VydmVyX3RpbWU9MTAvMjUvMjAyMyA1OjE1OjQ4IEFNJmhhc2hfdmFsdWU9Wm56b1BjMGVnTzF4c0ljYmsxMnZwQT09JnZhbGlkbWludXRlcz0xMjA=")
        cctvUrlList.add("https://cctvsecn01.ktict.co.kr:8082/koroad01106/L300021/playlist.m3u8?wmsAuthSign=c2VydmVyX3RpbWU9MTAvMjUvMjAyMyA1OjE3OjIyIEFNJmhhc2hfdmFsdWU9QWNWVEUzcVc5OWhTQ2d2cFdraTA0UT09JnZhbGlkbWludXRlcz0xMjA=")
        cctvUrlList.add("https://cctvsecn01.ktict.co.kr:8082/koroad01301/L300058/playlist.m3u8?wmsAuthSign=c2VydmVyX3RpbWU9MTAvMjUvMjAyMyA2OjMwOjQ5IEFNJmhhc2hfdmFsdWU9dWM5L1lSaXBVL0JTbGpGcFQ1bCswdz09JnZhbGlkbWludXRlcz0xMjA=")
        cctvUrlList.add("https://cctvsecn01.ktict.co.kr:8082/koroad01104/L300019/playlist.m3u8?wmsAuthSign=c2VydmVyX3RpbWU9MTAvMjUvMjAyMyA2OjMzOjA5IEFNJmhhc2hfdmFsdWU9TTNXcU9WdVI1c0F0T3V3Q2FRQnhnQT09JnZhbGlkbWludXRlcz0xMjA=")
        cctvUrlList.add("https://cctvsecn01.ktict.co.kr:8082/koroad01205/L300055/playlist.m3u8?wmsAuthSign=c2VydmVyX3RpbWU9MTAvMjUvMjAyMyA2OjM1OjQwIEFNJmhhc2hfdmFsdWU9ek1NYnRkUWxmOG9RS3ZPWm1NakhOUT09JnZhbGlkbWludXRlcz0xMjA=")
        cctvUrlList.add("https://cctvsecn01.ktict.co.kr:8082/koroad01302/L300031/playlist.m3u8?wmsAuthSign=c2VydmVyX3RpbWU9MTAvMjUvMjAyMyA2OjM3OjE1IEFNJmhhc2hfdmFsdWU9alQ1Q0tGdklRSEZUS3ZGMGpDM3hlUT09JnZhbGlkbWludXRlcz0xMjA=")
        cctvUrlList.add("https://cctvsecn01.ktict.co.kr:8082/koroad01201/L300051/playlist.m3u8?wmsAuthSign=c2VydmVyX3RpbWU9MTAvMjUvMjAyMyA2OjQyOjI3IEFNJmhhc2hfdmFsdWU9VjdTM2Rjb2lvVms1ZGdPM1I4NjZDQT09JnZhbGlkbWludXRlcz0xMjA=")
        cctvUrlList.add("https://cctvsecn01.ktict.co.kr:8082/koroad01307/L300036/playlist.m3u8?wmsAuthSign=c2VydmVyX3RpbWU9MTAvMjUvMjAyMyA2OjQzOjUxIEFNJmhhc2hfdmFsdWU9bTVhOE1IaWhYZjdtK055L1JlS2Z0UT09JnZhbGlkbWludXRlcz0xMjA=")
        cctvUrlList.add("https://cctvsecn01.ktict.co.kr:8082/koroad01407/L300073/playlist.m3u8?wmsAuthSign=c2VydmVyX3RpbWU9MTAvMjUvMjAyMyA2OjQ5OjI3IEFNJmhhc2hfdmFsdWU9dzRGWFZTdW5rUTdJdTdWQ2dYeDhwdz09JnZhbGlkbWludXRlcz0xMjA=")
        cctvUrlList.add("https://cctvsecn01.ktict.co.kr:8082/koroad01405/L300013/playlist.m3u8?wmsAuthSign=c2VydmVyX3RpbWU9MTAvMjUvMjAyMyA2OjUxOjU3IEFNJmhhc2hfdmFsdWU9TTlXZmM3L0lyOVpKTnpuOWNIU0RKQT09JnZhbGlkbWludXRlcz0xMjA=")
        cctvUrlList.add("https://cctvsecn01.ktict.co.kr:8082/koroad01305/L300006/playlist.m3u8?wmsAuthSign=c2VydmVyX3RpbWU9MTAvMjUvMjAyMyA2OjUzOjEzIEFNJmhhc2hfdmFsdWU9N3BuQ2JUUE1pRUtXMFExb2hIbk42Zz09JnZhbGlkbWludXRlcz0xMjA=")
        cctvUrlList.add("https://cctvsecn01.ktict.co.kr:8082/koroad01407/L300043/playlist.m3u8?wmsAuthSign=c2VydmVyX3RpbWU9MTAvMjUvMjAyMyA2OjU3OjQ4IEFNJmhhc2hfdmFsdWU9M1hzQjdSVE44M3JKU0hVTXdnVlo1dz09JnZhbGlkbWludXRlcz0xMjA=")
        cctvUrlList.add("https://cctvsecn01.ktict.co.kr:8082/koroad01305/L300034/playlist.m3u8?wmsAuthSign=c2VydmVyX3RpbWU9MTAvMjUvMjAyMyA3OjAzOjI1IEFNJmhhc2hfdmFsdWU9SkJYOGNHdU9DYnFwc0ZITW9SVzExdz09JnZhbGlkbWludXRlcz0xMjA=")
        cctvUrlList.add("https://cctvsecn01.ktict.co.kr:8082/koroad01407/L300015/playlist.m3u8?wmsAuthSign=c2VydmVyX3RpbWU9MTAvMjUvMjAyMyA3OjA1OjM0IEFNJmhhc2hfdmFsdWU9OVRuOEhienpPV2VKRVN0alRRK3Bydz09JnZhbGlkbWludXRlcz0xMjA=")
        cctvUrlList.add("https://cctvsecn01.ktict.co.kr:8082/koroad01406/L300014/playlist.m3u8?wmsAuthSign=c2VydmVyX3RpbWU9MTAvMjUvMjAyMyA3OjA3OjU2IEFNJmhhc2hfdmFsdWU9MU9tMVR3NGFzMERVYTNaU2JyRVFYUT09JnZhbGlkbWludXRlcz0xMjA=")
        cctvUrlList.add("https://cctvsecn01.ktict.co.kr:8082/koroad01207/L300057/playlist.m3u8?wmsAuthSign=c2VydmVyX3RpbWU9MTAvMjUvMjAyMyA3OjEwOjAxIEFNJmhhc2hfdmFsdWU9Wmhjb09EaDl2K0piNjg3YUpVanVRUT09JnZhbGlkbWludXRlcz0xMjA=")
        cctvUrlList.add("https://cctvsecn01.ktict.co.kr:8082/koroad01306/L300035/playlist.m3u8?wmsAuthSign=c2VydmVyX3RpbWU9MTAvMjUvMjAyMyA3OjE1OjEwIEFNJmhhc2hfdmFsdWU9YlFoMGRUMlNDYUR3TmxxU1dQYnhRdz09JnZhbGlkbWludXRlcz0xMjA=")
        cctvUrlList.add("https://cctvsecn01.ktict.co.kr:8082/koroad01103/L300046/playlist.m3u8?wmsAuthSign=c2VydmVyX3RpbWU9MTAvMjUvMjAyMyA3OjE2OjUxIEFNJmhhc2hfdmFsdWU9UW9GL0FodmM1STN0Y3lnZXNRMTl3UT09JnZhbGlkbWludXRlcz0xMjA=")
        cctvUrlList.add("https://cctvsecn01.ktict.co.kr:8082/koroad01304/L300005/playlist.m3u8?wmsAuthSign=c2VydmVyX3RpbWU9MTAvMjUvMjAyMyA3OjIwOjA3IEFNJmhhc2hfdmFsdWU9dkJXRnhEZmxqaythbnZ6VVNQUU1Fdz09JnZhbGlkbWludXRlcz0xMjA=")
        cctvUrlList.add("https://cctvsecn01.ktict.co.kr:8082/koroad01102/L300045/playlist.m3u8?wmsAuthSign=c2VydmVyX3RpbWU9MTAvMjUvMjAyMyA3OjIwOjQ4IEFNJmhhc2hfdmFsdWU9dzBpK0sxTlk3SEdyV1BKVUpiMHpnUT09JnZhbGlkbWludXRlcz0xMjA=")
        cctvUrlList.add("https://cctvsecn01.ktict.co.kr:8082/koroad01301/L300002/playlist.m3u8?wmsAuthSign=c2VydmVyX3RpbWU9MTAvMjUvMjAyMyA3OjIyOjAzIEFNJmhhc2hfdmFsdWU9enFveUpOTmtrNXRRRDlUbWkxM2lDQT09JnZhbGlkbWludXRlcz0xMjA=")
        cctvUrlList.add("https://cctvsecn01.ktict.co.kr:8082/koroad01107/L300022/playlist.m3u8?wmsAuthSign=c2VydmVyX3RpbWU9MTAvMjUvMjAyMyA3OjIyOjUwIEFNJmhhc2hfdmFsdWU9Z2J1ZUVVRXdrcmgyZFNyYTlhTzJ5QT09JnZhbGlkbWludXRlcz0xMjA=")
        cctvUrlList.add("https://cctvsecn01.ktict.co.kr:8082/koroad01105/L300048/playlist.m3u8?wmsAuthSign=c2VydmVyX3RpbWU9MTAvMjUvMjAyMyA3OjIzOjM0IEFNJmhhc2hfdmFsdWU9c2pUbjA2U2V2ZTJ3ejUxcTJPREZBUT09JnZhbGlkbWludXRlcz0xMjA=")
        cctvUrlList.add("https://cctvsecn01.ktict.co.kr:8082/koroad01201/L300023/playlist.m3u8?wmsAuthSign=c2VydmVyX3RpbWU9MTAvMjUvMjAyMyA3OjI0OjQ4IEFNJmhhc2hfdmFsdWU9a1JhZWZPSWxEbEFRcE9Pb3A1bEt5QT09JnZhbGlkbWludXRlcz0xMjA=")
        cctvUrlList.add("https://cctvsecn01.ktict.co.kr:8082/koroad01307/L300066/playlist.m3u8?wmsAuthSign=c2VydmVyX3RpbWU9MTAvMjUvMjAyMyA3OjI1OjQxIEFNJmhhc2hfdmFsdWU9SWR5VzRTeW5FU2FHSHFnTDIzbzQrZz09JnZhbGlkbWludXRlcz0xMjA=")
        cctvUrlList.add("https://cctvsecn01.ktict.co.kr:8082/koroad01207/L300001/playlist.m3u8?wmsAuthSign=c2VydmVyX3RpbWU9MTAvMjUvMjAyMyA3OjI2OjMwIEFNJmhhc2hfdmFsdWU9UW83d1RDU0E2Z012UVJTaG42MzRXQT09JnZhbGlkbWludXRlcz0xMjA=")
        cctvUrlList.add("https://cctvsecn01.ktict.co.kr:8082/koroad01202/L300052/playlist.m3u8?wmsAuthSign=c2VydmVyX3RpbWU9MTAvMjUvMjAyMyA3OjI3OjI2IEFNJmhhc2hfdmFsdWU9MmJRaUVLY3huMGQ5ZGh3cGVPeEF6Zz09JnZhbGlkbWludXRlcz0xMjA=")
        cctvUrlList.add("https://cctvsecn01.ktict.co.kr:8082/koroad01401/L300067/playlist.m3u8?wmsAuthSign=c2VydmVyX3RpbWU9MTAvMjUvMjAyMyA3OjI4OjQzIEFNJmhhc2hfdmFsdWU9eWYwSFFKd3VqeXhRVFJmcVdBNldEdz09JnZhbGlkbWludXRlcz0xMjA=")
        cctvUrlList.add("https://cctvsecn01.ktict.co.kr:8082/koroad01207/L300029/playlist.m3u8?wmsAuthSign=c2VydmVyX3RpbWU9MTAvMjUvMjAyMyA3OjMwOjEyIEFNJmhhc2hfdmFsdWU9bnlTNkNha1ZacjhXd2Y4SmxOYW41QT09JnZhbGlkbWludXRlcz0xMjA=")
        cctvUrlList.add("https://cctvsecn01.ktict.co.kr:8082/koroad01302/L300059/playlist.m3u8?wmsAuthSign=c2VydmVyX3RpbWU9MTAvMjUvMjAyMyA3OjM3OjQ4IEFNJmhhc2hfdmFsdWU9eWdXYkhDYXdUOHlNRkJhYWZ6WkpNUT09JnZhbGlkbWludXRlcz0xMjA=")
        println(latLngList.size)
        println(cctvNameList.size)
        println(cctvUrlList.size)
        addMarkersInBatches(latLngList.mapIndexed { index, latLng ->
            MarkerSpec(latLng, cctvNameList[index], cctvNameList[index], cctvUrlList[index], false)
        })
    }

    private fun getItsResponse(type: String) {
        val cctvInfoService = ItsCCTVInfo().retrofit.create(ItsCCTVService::class.java)
        cctvInfoService.getCCTVInfo(
            apiKey = this@MainActivity.getString(R.string.its_cctv_key),
            type = type,
            cctvType = "1",
            minX = 124.0,
            maxX = 132.0,
            minY = 33.0,
            maxY = 43.0,
            getType = "json"
        ).enqueue(object : Callback<ItsCCTVResponseEntity> {
            override fun onResponse(
                call: Call<ItsCCTVResponseEntity>, response: Response<ItsCCTVResponseEntity>
            ) {
                if (response.isSuccessful) {
//                    println(response.body()?.response?.data?.size)
                    addMarkersInBatches(response.body()?.response?.data.orEmpty().map { data ->
                        MarkerSpec(LatLng(data.coordy, data.coordx), data.cctvname, data.cctvname, data.cctvurl, false)
                    })
                }
            }

            override fun onFailure(call: Call<ItsCCTVResponseEntity>, t: Throwable) {
                println(t.localizedMessage)
                t.printStackTrace()
            }
        })
    }

    private fun getJeJuCCTVResponse() {
        val jejuCCTVService = JeJuCCTVInfo().retrofit.create(JeJuCCTVService::class.java)
        jejuCCTVService.getCCTVInfo(getString(R.string.jeju_cctv_key)).enqueue(
            object : Callback<JeJuCCTVResponseEntity> {
                override fun onResponse(
                    call: Call<JeJuCCTVResponseEntity>,
                    response: Response<JeJuCCTVResponseEntity>
                ) {
                    if (response.isSuccessful) {
                        addMarkersInBatches(response.body()?.response?.body?.items?.item.orEmpty().map { data ->
                            MarkerSpec(
                                LatLng(data.laCrdnt.toDouble(), data.loCrdnt.toDouble()),
                                data.spotNm, data.spotNm, data.cctvUrl, false
                            )
                        })
                    }
                }

                override fun onFailure(call: Call<JeJuCCTVResponseEntity>, t: Throwable) {
                    println(t.localizedMessage)
                    t.printStackTrace()
                }
            },
        )

    }

    private fun getResponse(startIndex: Int, endIndex: Int) {
        val publicBicycleService = PublicBicycleInfo().retrofit.create(SeoulAPIService::class.java)
        publicBicycleService.gettbCycleStationInfo(
            this.getString(R.string.seoul_api_key), "json", "bikeList", startIndex, endIndex
        ).enqueue(object : Callback<PublicSeoulBicycleEntity> {
            override fun onResponse(
                call: Call<PublicSeoulBicycleEntity>, response: Response<PublicSeoulBicycleEntity>
            ) {
                if (response.isSuccessful) {
                    addMarkersInBatches(response.body()?.rentBikeStatus?.row.orEmpty().mapNotNull { data ->
                        val lat = data.stationLatitude.toDouble()
                        val lng = data.stationLongitude.toDouble()
                        if (lat in 33.0..43.0 && lng in 124.0..132.0) {
                            // 대여소 이름에서 숫자와 점(번호)을 제거
                            val name = data.stationName.filter { !it.isDigit() && it != '.' }.trim()
                            MarkerSpec(LatLng(lat, lng), name, "$name\n남은 자전거 수=${data.parkingBikeTotCnt}", null, true)
                        } else null
                    })
                }
            }

            override fun onFailure(call: Call<PublicSeoulBicycleEntity>, t: Throwable) {
                println(t.localizedMessage)
                t.printStackTrace()
            }
        })
    }

    override fun onPause() {
        super.onPause()
        binding.mapView.onPause()
    }

    override fun onStop() {
        super.onStop()
        lifecycleState = lifecycle.currentState.toString()
        binding.mapView.onStop()
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacksAndMessages(null)
        ioExecutor.shutdownNow()
        binding.mapView.onDestroy()
    }

    /**
     * 마커를 [MARKER_BATCH_SIZE]개씩 나눠서 생성한다.
     * 수천~수만 개를 한 번에 만들면 메인 스레드가 수십 초간 막혀 ANR 이 발생하므로,
     * 배치 사이에 메인 루프에 양보해 터치/렌더링이 계속 처리되도록 한다.
     */
    private fun addMarkersInBatches(specs: List<MarkerSpec>) {
        if (specs.isEmpty()) return
        var next = 0
        mainHandler.post(object : Runnable {
            override fun run() {
                if (isDestroyed) return
                val end = minOf(next + MARKER_BATCH_SIZE, specs.size)
                for (i in next until end) markers.add(createMarker(specs[i]))
                next = end
                if (next < specs.size) mainHandler.post(this)
            }
        })
    }

    private fun createMarker(spec: MarkerSpec) = Marker().apply {
        position = spec.position
        captionText = spec.caption
        if (spec.isBike) {
            icon = bikeIcon
            width = BIKE_ICON_SIZE
            height = BIKE_ICON_SIZE
        } else {
            icon = this@MainActivity.icon
            width = WIDTH
            height = HEIGHT
            globalZIndex = CCTV_Z_INDEX // 따릉이 마커보다 위에 표시
        }
        tag = MarkerTag(spec.infoText, spec.videoUrl)
        onClickListener = markerClickListener
        map = naverMap // 모든 속성을 설정한 뒤 마지막에 지도에 추가
    }

    // 엑셀 파싱은 백그라운드 스레드에서, 마커 생성은 메인 스레드에서 나눠서 처리
    private fun loadExcelCctvAsync() {
        ioExecutor.execute {
            val specs = try {
                parseExcel().map { data ->
                    MarkerSpec(
                        LatLng(data.yCoord, data.xCoord),
                        data.CCTVName, data.CCTVName, data.CCTVUrl, false
                    )
                }
            } catch (e: Exception) {
                e.printStackTrace()
                emptyList()
            }
            runOnUiThread { addMarkersInBatches(specs) }
        }
    }

    // 지도가 준비가 되면 호출됨
    override fun onMapReady(mapObject: NaverMap) {
        // 인자로 온 NaverMap 객체를 받아옴
        naverMap = mapObject
        naverMap.uiSettings.isLocationButtonEnabled = true
        naverMap.uiSettings.isZoomControlEnabled = true
        naverMap.locationSource = locationSource

        ActivityCompat.requestPermissions(
            this, arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION
            ), LOCATION_PERMISSION_REQUEST_CODE
        )
        // 지도의 빈 곳을 누르면 열려 있는 정보창을 닫음
        naverMap.setOnMapClickListener { _, _ -> sharedInfoWindow.close() }

        // 엑셀 불러오기 (백그라운드에서 파싱)
        loadExcelCctvAsync()
        getItsResponse("ex")
        getItsResponse("its")
        getResponse(1, 1000)
        getResponse(1001, 2000)
        getResponse(2001, 2712)
        getJeJuCCTVResponse()
        getDaejeonCCTVData()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        binding.mapView.onLowMemory()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        binding.mapView.onSaveInstanceState(outState)
    }

    private fun showDialog() {
        AlertDialog.Builder(this).apply {
            setTitle("위치 정보를 얻기위해서는 위치 권한이 필요합니다")
            setPositiveButton("허용") { _, _ ->
                val findLocation = ActivityCompat.shouldShowRequestPermissionRationale(
                    this@MainActivity, Manifest.permission.ACCESS_FINE_LOCATION
                )
                val courseLocation = ActivityCompat.shouldShowRequestPermissionRationale(
                    this@MainActivity, Manifest.permission.ACCESS_COARSE_LOCATION
                )
                if (findLocation && courseLocation) {
                    ActivityCompat.requestPermissions(
                        this@MainActivity, arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                        ), LOCATION_PERMISSION_REQUEST_CODE
                    )
                } else {
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", packageName, null)
                    }
                    startActivity(intent)
                    finish()
                }
            }
            setNegativeButton("취소", null)
            show()
        }
    }

    private fun getCroppedBitmap(bitmap: Bitmap): Bitmap {
        val output = Bitmap.createBitmap(
            bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888
        )
        val canvas = Canvas(output)
        val color = -0xbdbdbe
        val paint = Paint()
        val rect = Rect(0, 0, bitmap.width, bitmap.height)
        paint.isAntiAlias = true
        canvas.drawARGB(0, 0, 0, 0)
        paint.color = color
        canvas.drawCircle(
            (bitmap.width / 2).toFloat(),
            (bitmap.height / 2).toFloat(),
            (bitmap.width / 2).toFloat(),
            paint
        )
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        canvas.drawBitmap(bitmap, rect, rect, paint)
        return output
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray
    ) {
        if (locationSource.onRequestPermissionsResult(requestCode, permissions, grantResults)) {
            if (!locationSource.isActivated) { // 권한 거부됨
                naverMap.locationTrackingMode = LocationTrackingMode.None
            } else {
                naverMap.locationTrackingMode = LocationTrackingMode.Follow
            }
            return
        }
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    // camera를 움직이는 함수
    private fun moveCamera(position: LatLng) {
        // CameraUpdate 객체 생성 및 설정
        // animation을 CameraAnimation.Easing=부드럽게 가감속하며 이동, 가까운 거리를 이동할 때 적합
        // 확대할 정도를 갖는 ZoomLevel 값 만큼 확대
        // 위경도 정보를 갖고 있는 LatLng 객체(position)
        val cameraUpdate =
            CameraUpdate.scrollAndZoomTo(position, 17.0).animate(CameraAnimation.Easing)
        // naverMap의 카메라를 cameraUpdate로 이동한다.
        naverMap.moveCamera(cameraUpdate)
    }

    private fun parseExcel(): List<ExcelData> {
        val assetManager = assets
        val inputStream = assetManager.open("OpenDataCCTV.xlsx") // 파일 열기
        val myWorkBook = WorkbookFactory.create(inputStream) // 불러온 파일로 워크북 생성하기

        val sheet = myWorkBook.getSheetAt(0) // 읽어올 엑셀의 시트 index 입력

        val rowIterator = sheet.iterator()

        val list = ArrayList<ExcelData>()

        var rowNo = 0

        while (rowIterator.hasNext()) {
            val data = ExcelData(0, "", "", "", 0.0, 0.0, "")
            val myRow = rowIterator.next() as XSSFRow // HSSFRow 로 캐스팅할 경우 .xls 파일 읽음
            if (rowNo != 0) {
                val cellIterator = myRow.iterator()

                var colNo = 0

                while (cellIterator.hasNext()) {
                    val myCell = cellIterator.next() as XSSFCell // HSSFCell 로 캐스팅할 경우 .xls 파일 읽음

                    // 있는 모든 열 읽기
                    when (colNo) {
                        0 -> {
                            data.num = myCell.toString().toLong()
                        }

                        1 -> {
                            data.id = myCell.toString()
                        }

                        2 -> {
                            data.CCTVName = myCell.toString()
                        }

                        3 -> {
                            data.centerName = myCell.toString()
                        }

                        4 -> {
                            data.xCoord = myCell.toString().toDouble()
                        }

                        5 -> {
                            data.yCoord = myCell.toString().toDouble()
                        }

                        6 -> {
                            data.CCTVUrl = myCell.toString()
                            list.add(data)
                        }
                    }
                    // 필요한 경우 해당 데이터 들을 배열이나 리스트에 담아 사용하면 됩니다.
                    colNo++
                }
            }
            rowNo++
        }
        inputStream.close()
        return list
    }
}