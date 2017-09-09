package com.mapbox.storelocator.model

import com.mapbox.mapboxsdk.geometry.LatLng
import org.junit.After
import org.junit.Before
import org.junit.Test

import org.junit.Assert.*

/**
 * Created by shavit on 9/9/17.
 */
class IndividualLocationTest {
    @Before
    fun setUp() {
    }

    @After
    fun tearDown() {
    }

    @Test
    fun createIndividualLocation() {
        var name: String? = "Location 1"
        val address = ""
        val hours = "9am - 4pm"
        val phoneNum = "(555) 555-9430"
        val location = LatLng(-73.970048, 40.789752)
        val mIndividualLocation = IndividualLocation(name, address, hours, phoneNum, location)

        assert(mIndividualLocation.name == "Location 1")
        assert(mIndividualLocation.address == "")
        assert(mIndividualLocation.hours == "9am - 4pm")
        assert(mIndividualLocation.phoneNum == "(555) 555-9430")
        assert(mIndividualLocation.location.latitude == -73.970048)
        assert(mIndividualLocation.location.longitude == 40.789752)
    }

}