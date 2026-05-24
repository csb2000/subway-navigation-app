package com.example.a260511

import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST
import java.util.concurrent.TimeUnit

data class WifiAp(val bssid: String, val rssi: Int)
data class LocateRequest(val wifi: List<WifiAp>)
data class LocateResponse(val node: String)
data class RouteRequest(val from: String, val to: String)
data class RouteNode(
    val node: String,
    val floor: String?,
    val zone: String?,
    val edge_to_next: String?
)
data class RouteResponse(val path: List<RouteNode>)
data class DirectionRequest(val from: String, val to: String)
data class DirectionResponse(val angle: Float, val cardinal: String?, val clock: Int?)

interface SubwayApi {
    @POST("/locate")
    suspend fun locate(@Body req: LocateRequest): LocateResponse

    @POST("/route")
    suspend fun route(@Body req: RouteRequest): RouteResponse

    @POST("/direction")
    suspend fun direction(@Body req: DirectionRequest): DirectionResponse
}

object ApiClient {
    private const val BASE_URL = "http://10.0.2.2:5000"

    private val logging = HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.BODY
    }

    private val client = OkHttpClient.Builder()
        .addInterceptor(logging)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    val api: SubwayApi = Retrofit.Builder()
        .baseUrl(BASE_URL)
        .client(client)
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(SubwayApi::class.java)
}

val nodeNameMap = mapOf(
    "station_exit" to "역 출입구",
    "fare_gate" to "개찰구",
    "floor1_hall" to "1층 홀",
    "floor1_stairs" to "1층 계단",
    "stairs_mid" to "계단 중간",
    "b1_stairs" to "지하 계단",
    "b1_elevator" to "지하 엘리베이터 앞",
    "b1_down_stairs_front" to "하행 계단 앞",
    "down_platform" to "하행 승강장",
    "b1_up_stairs_front" to "상행 계단 앞",
    "up_platform" to "상행 승강장"
)