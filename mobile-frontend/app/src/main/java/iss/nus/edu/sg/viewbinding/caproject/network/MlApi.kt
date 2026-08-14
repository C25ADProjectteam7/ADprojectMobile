package iss.nus.edu.sg.viewbinding.caproject.network

import iss.nus.edu.sg.viewbinding.caproject.network.model.ml.HotelPricePredictionRequest
import iss.nus.edu.sg.viewbinding.caproject.network.model.ml.HotelPricePredictionResponse
import iss.nus.edu.sg.viewbinding.caproject.network.model.ml.HotelFairPriceRequest
import iss.nus.edu.sg.viewbinding.caproject.network.model.ml.HotelFairPriceResponse
import retrofit2.http.Body
import retrofit2.http.POST

interface MlApi {

    @POST("api/ml/predict-hotel-price")
    suspend fun predictHotelPrice(
        @Body request: HotelPricePredictionRequest,
    ): HotelPricePredictionResponse

    @POST("api/ml/v2/hotel-price")
    suspend fun predictHotelFairPrice(
        @Body request: HotelFairPriceRequest,
    ): HotelFairPriceResponse
}
