package iss.nus.edu.sg.viewbinding.caproject.network

import iss.nus.edu.sg.viewbinding.caproject.network.model.ml.HotelFairPriceRequest
import iss.nus.edu.sg.viewbinding.caproject.network.model.ml.HotelFairPriceResponse
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

    /**
     * India hotel fair price (V3). Spring proxies this to the ML service's
     * /api/ml/v2/hotel-price/by-hotel-id - the app never calls Python directly.
     */
    @POST("api/ml/v2/hotel-price")
    suspend fun predictHotelFairPrice(
        @Body request: HotelFairPriceRequest,
    ): HotelFairPriceResponse

    @POST("api/ml/v2/price-advice")
    suspend fun priceAdvice(
        @Body request: PriceAdviceRequest,
    ): PriceAdviceResponse
}
