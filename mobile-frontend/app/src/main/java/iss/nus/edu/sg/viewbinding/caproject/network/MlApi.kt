package iss.nus.edu.sg.viewbinding.caproject.network

import iss.nus.edu.sg.viewbinding.caproject.network.model.ml.HotelPricePredictionRequest
import iss.nus.edu.sg.viewbinding.caproject.network.model.ml.HotelPricePredictionResponse
import iss.nus.edu.sg.viewbinding.caproject.network.model.ml.PriceAdviceRequest
import iss.nus.edu.sg.viewbinding.caproject.network.model.ml.PriceAdviceResponse
import retrofit2.http.Body
import retrofit2.http.POST

interface MlApi {

    @POST("api/ml/predict-hotel-price")
    suspend fun predictHotelPrice(
        @Body request: HotelPricePredictionRequest,
    ): HotelPricePredictionResponse

    @POST("api/ml/v2/price-advice")
    suspend fun priceAdvice(
        @Body request: PriceAdviceRequest,
    ): PriceAdviceResponse
}
