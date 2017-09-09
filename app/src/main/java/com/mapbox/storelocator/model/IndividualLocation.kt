package com.mapbox.storelocator.model

import com.mapbox.mapboxsdk.geometry.LatLng

/**
 * POJO class for an individual location
 */
class IndividualLocation(var name: String?, val address: String, val hours: String, val phoneNum: String, val location: LatLng) {
    var distance: String? = null
}
