package com.mapbox.storelocator.activity

import android.content.Context
import android.graphics.Color
import android.net.ConnectivityManager
import android.os.Bundle
import android.support.v7.app.AppCompatActivity
import android.support.v7.widget.LinearSnapHelper
import android.support.v7.widget.RecyclerView
import android.util.Log
import android.view.Window
import android.view.WindowManager
import android.widget.ImageView
import android.widget.Toast

import com.mapbox.androidsdk.plugins.building.BuildingPlugin
import com.mapbox.mapboxsdk.Mapbox
import com.mapbox.mapboxsdk.annotations.Icon
import com.mapbox.mapboxsdk.annotations.IconFactory
import com.mapbox.mapboxsdk.annotations.Marker
import com.mapbox.mapboxsdk.annotations.MarkerOptions
import com.mapbox.mapboxsdk.annotations.PolylineOptions
import com.mapbox.mapboxsdk.camera.CameraPosition
import com.mapbox.mapboxsdk.camera.CameraUpdateFactory
import com.mapbox.mapboxsdk.constants.Style
import com.mapbox.mapboxsdk.geometry.LatLng
import com.mapbox.mapboxsdk.geometry.LatLngBounds
import com.mapbox.mapboxsdk.maps.MapView
import com.mapbox.mapboxsdk.maps.MapboxMap
import com.mapbox.services.api.directions.v5.DirectionsCriteria
import com.mapbox.services.api.directions.v5.MapboxDirections
import com.mapbox.services.api.directions.v5.models.DirectionsResponse
import com.mapbox.services.api.directions.v5.models.DirectionsRoute
import com.mapbox.services.api.utils.turf.TurfHelpers
import com.mapbox.services.commons.geojson.FeatureCollection
import com.mapbox.services.commons.geojson.LineString
import com.mapbox.services.commons.models.Position
import com.mapbox.storelocator.R
import com.mapbox.storelocator.adapter.LocationRecyclerViewAdapter
import com.mapbox.storelocator.model.IndividualLocation
import com.mapbox.storelocator.util.LinearLayoutManagerWithSmoothScroller

import java.io.IOException
import java.text.DecimalFormat
import java.util.ArrayList

import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

import com.mapbox.services.Constants.PRECISION_6
import com.mapbox.storelocator.util.mDirectionsBuilder

/**
 * Activity with a Mapbox map and recyclerview to view various locations
 */
class MapActivity : AppCompatActivity(), LocationRecyclerViewAdapter.ClickListener {
    private var currentRoute: DirectionsRoute? = null
    private var featureCollection: FeatureCollection? = null
    private var mapboxMap: MapboxMap? = null
    private var mapView: MapView? = null
    private var directionsApiClient: MapboxDirections? = null
    private var locationsRecyclerView: RecyclerView? = null
    private var listOfIndividualLocations: ArrayList<IndividualLocation>? = null
    private var customThemeManager: CustomThemeManager? = null
    private var styleRvAdapter: LocationRecyclerViewAdapter? = null
    private var chosenTheme: Int = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Configure the Mapbox access token. Configuration can either be called in your application
        // class or in the same activity which contains the mapview.
        Mapbox.getInstance(this, getString(R.string.access_token))

        // Hide the status bar for the map to fill the entire screen
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        this.window.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN)

        // Inflate the layout with the the MapView. Always inflate this after the Mapbox access token is configured.
        setContentView(R.layout.activity_map)

        // Create a GeoJSON feature collection from the GeoJSON file in the assets folder.
        try {
            getFeatureCollectionFromJson()
        } catch (exception: Exception) {
            Log.e("MapActivity", "onCreate: " + exception)
            Toast.makeText(this, R.string.failure_to_load_file, Toast.LENGTH_LONG).show()
        }

        // Initialize a list of IndividualLocation objects for future use with recyclerview
        listOfIndividualLocations = ArrayList()

        // Initialize the theme that was selected in the previous activity. The blue theme is set as the backup default.
        chosenTheme = getChosenTheme()

        // Set up the Mapbox map
        mapView = findViewById(R.id.mapView)
        mapView!!.onCreate(savedInstanceState)
        mapView!!.getMapAsync { mapboxMap ->
            // Setting the returned mapboxMap object (directly above) equal to the "globally declared" one
            this@MapActivity.mapboxMap = mapboxMap

            // Initialize the custom class that handles marker icon creation and map styling based on the selected theme
            customThemeManager = CustomThemeManager(chosenTheme, this@MapActivity, mapView!!, mapboxMap)
            customThemeManager!!.initializeTheme()

            // Adjust the opacity of the Mapbox logo in the lower left hand corner of the map
            val logo = mapView!!.findViewById<ImageView>(R.id.logoView)
            logo.imageAlpha = MAPBOX_LOGO_OPACITY

            // Set bounds for the map camera so that the user can't pan the map outside of the NYC area
            mapboxMap.setLatLngBoundsForCameraTarget(LOCKED_MAP_CAMERA_BOUNDS)

            // Create a list of features from the feature collection
            val featureList = featureCollection!!.features

            // Loop through the locations to add markers to the map
            for (x in featureList.indices) {

                val singleLocation = featureList[x]

                // Get the single location's String properties to place in its map marker
                val singleLocationName = singleLocation.getStringProperty("name")
                val singleLocationHours = singleLocation.getStringProperty("hours")
                val singleLocationDescription = singleLocation.getStringProperty("description")
                val singleLocationPhoneNum = singleLocation.getStringProperty("phone")

                // Get the single location's LatLng coordinates
                val singleLocationPosition = singleLocation.geometry.coordinates as Position

                // Create a new LatLng object with the Position object created above
                val singleLocationLatLng = LatLng(singleLocationPosition.latitude,
                        singleLocationPosition.longitude)

                // Add the location to the Arraylist of locations for later use in the recyclerview
                listOfIndividualLocations!!.add(IndividualLocation(
                        singleLocationName,
                        singleLocationDescription,
                        singleLocationHours,
                        singleLocationPhoneNum,
                        singleLocationLatLng
                ))

                // Add the location's marker to the map
                mapboxMap.addMarker(MarkerOptions()
                        .position(singleLocationLatLng)
                        .title(singleLocationName)
                        .icon(customThemeManager!!.unselectedMarkerIcon))

                // Call getInformationFromDirectionsApi() to eventually display the location's
                // distance from mocked device location
                getInformationFromDirectionsApi(singleLocationLatLng.latitude,
                        singleLocationLatLng.longitude, false, x)
            }

            // Add the fake device location marker to the map. In a real use case scenario, the Mapbox location layer plugin
            // can be used to easily display the device's location
            addMockDeviceLocationMarkerToMap()

            setUpMarkerClickListener()

            setUpRecyclerViewOfLocationCards(chosenTheme)
        }
    }

    override fun onItemClick(position: Int) {

        // Get the selected individual location via its card's position in the recyclerview of cards
        val selectedLocation = listOfIndividualLocations!![position]

        // Retrieve and change the selected card's marker to the selected marker icon
        val markerTiedToSelectedCard = mapboxMap!!.markers[position]
        adjustMarkerSelectStateIcons(markerTiedToSelectedCard)

        // Reposition the map camera target to the selected marker
        val selectedLocationLatLng = selectedLocation.location
        repositionMapCamera(selectedLocationLatLng)

        // Check for an internet connection before making the call to Mapbox Directions API
        if (deviceHasInternetConnection()) {
            // Start call to the Mapbox Directions API
            getInformationFromDirectionsApi(selectedLocationLatLng.latitude,
                    selectedLocationLatLng.longitude, true, null)
        } else {
            Toast.makeText(this, R.string.no_internet_message, Toast.LENGTH_LONG).show()
        }
    }

    private fun getInformationFromDirectionsApi(destinationLatCoordinate: Double, destinationLongCoordinate: Double,
                                                fromMarkerClick: Boolean, listIndex: Int?) {
        // Set up origin and destination coordinates for the call to the Mapbox Directions API
        val mockCurrentLocation = Position.fromLngLat(MOCK_DEVICE_LOCATION_LAT_LNG.longitude,
                MOCK_DEVICE_LOCATION_LAT_LNG.latitude)
        val destinationMarker = Position.fromLngLat(destinationLongCoordinate, destinationLatCoordinate)

        // Initialize the directionsApiClient object for eventually drawing a navigation route on the map
        directionsApiClient = mDirectionsBuilder().Builder()
                .setOrigin(mockCurrentLocation)
                .setDestination(destinationMarker)
                .setOverview(DirectionsCriteria.OVERVIEW_FULL)
                .setProfile(DirectionsCriteria.PROFILE_DRIVING)
                .setAccessToken(getString(R.string.access_token))
                .build()

        directionsApiClient!!.enqueueCall(object : Callback<DirectionsResponse> {
            override fun onResponse(call: Call<DirectionsResponse>, response: Response<DirectionsResponse>) {
                // Check that the response isn't null and that the response has a route
                if (response.body() == null) {
                    Log.e("MapActivity", "No routes found, make sure you set the right user and access token.")
                } else if (response.body().routes.size < 1) {
                    Log.e("MapActivity", "No routes found")
                } else {
                    if (fromMarkerClick) {
                        // Retrieve and draw the navigation route on the map
                        currentRoute = response.body().routes[0]
                        drawNavigationPolylineRoute(currentRoute)
                    } else {
                        // Use Mapbox Turf helper method to convert meters to miles and then format the mileage number
                        val df = DecimalFormat("#.#")
                        val finalConvertedFormattedDistance = df.format(TurfHelpers.convertDistance(
                                response.body().routes[0].distance, "meters", "miles")).toString()

                        // Set the distance for each location object in the list of locations
                        if (listIndex != null) {
                            listOfIndividualLocations!![listIndex].distance = finalConvertedFormattedDistance
                            // Refresh the displayed recyclerview when the location's distance is set
                            styleRvAdapter!!.notifyDataSetChanged()
                        }
                    }
                }
            }

            override fun onFailure(call: Call<DirectionsResponse>, throwable: Throwable) {
                Toast.makeText(this@MapActivity, R.string.failure_to_retrieve, Toast.LENGTH_LONG).show()
            }
        })
    }

    private fun getChosenTheme(): Int {
        // R.style.AppTheme_Purple
        // R.style.AppTheme_Blue
        // R.style.AppTheme_Green
        // R.style.AppTheme_Neutral
        // R.style.AppTheme_Gray
        // R.style.AppTheme_PapaBless

        return R.style.AppTheme_PapaBless
    }

    private fun repositionMapCamera(newTarget: LatLng) {
        val newCameraPosition = CameraPosition.Builder()
                .target(newTarget)
                .build()
        mapboxMap!!.animateCamera(CameraUpdateFactory.newCameraPosition(newCameraPosition), CAMERA_MOVEMENT_SPEED_IN_MILSECS)
    }

    private fun addMockDeviceLocationMarkerToMap() {
        // Add the fake user location marker to the map
        mapboxMap!!.addMarker(MarkerOptions()
                .position(MOCK_DEVICE_LOCATION_LAT_LNG)
                .title(getString(R.string.mock_location_title))
                .icon(customThemeManager!!.mockLocationIcon))
    }

    @Throws(IOException::class)
    private fun getFeatureCollectionFromJson() {
        try {
            // Use fromJson() method to convert the GeoJSON file into a usable FeatureCollection object
            featureCollection = FeatureCollection.fromJson(loadGeoJsonFromAsset("list_of_locations.geojson"))
        } catch (exception: Exception) {
            Log.e("MapActivity", "getFeatureCollectionFromJson: " + exception)
        }

    }

    private fun loadGeoJsonFromAsset(filename: String): String? {
        var res: String? = null
        try {
            val f = assets.open(filename)
            val size = f.available()
            val buf = ByteArray(size)
            f.read(buf)
            f.close()
            res = String(buf)
        } catch (exception: Exception){
            Log.e("MapActivity", "Exception Loading GeoJSON: " + exception.toString())
            exception.printStackTrace()
        }

        return res
    }

    private fun setUpRecyclerViewOfLocationCards(chosenTheme: Int) {
        // Initialize the recyclerview of location cards and a custom class for automatic card scrolling
        locationsRecyclerView = findViewById(R.id.map_layout_rv)
        locationsRecyclerView!!.setHasFixedSize(true)
        locationsRecyclerView!!.layoutManager = LinearLayoutManagerWithSmoothScroller(this)
        styleRvAdapter = LocationRecyclerViewAdapter(listOfIndividualLocations,
                applicationContext, this, chosenTheme)
        locationsRecyclerView!!.adapter = styleRvAdapter
        val snapHelper = LinearSnapHelper()
        snapHelper.attachToRecyclerView(locationsRecyclerView)
    }

    private fun setUpMarkerClickListener() {
        mapboxMap!!.setOnMarkerClickListener { marker ->
            // Get the position of the selected marker
            val positionOfSelectedMarker = marker.position

            // Check that the selected marker isn't the mock device location marker
            if (marker.position != MOCK_DEVICE_LOCATION_LAT_LNG) {

                for (x in 0..mapboxMap!!.markers.size - 1) {
                    if (mapboxMap!!.markers[x].position === positionOfSelectedMarker) {
                        // Scroll the recyclerview to the selected marker's card. It's "x-1" below because
                        // the mock device location marker is part of the marker list but doesn't have its own card
                        // in the actual recyclerview.
                        locationsRecyclerView!!.smoothScrollToPosition(x)
                    }
                }
                adjustMarkerSelectStateIcons(marker)
            }
            // Return true so that the selected marker's info window doesn't pop up
            true
        }
    }

    private fun adjustMarkerSelectStateIcons(marker: Marker) {
        // Set all of the markers' icons to the unselected marker icon
        for (singleMarker in mapboxMap!!.markers) {
            if (singleMarker.title != getString(R.string.mock_location_title)) {
                singleMarker.icon = customThemeManager!!.unselectedMarkerIcon
            }
        }

        // Change the selected marker's icon to a selected state marker except if the mock device location marker is selected
        if (marker.icon != customThemeManager!!.mockLocationIcon) {
            marker.icon = customThemeManager!!.selectedMarkerIcon
        }

        // Get the directionsApiClient route to the selected marker except if the mock device location marker is selected
        if (marker.icon != customThemeManager!!.mockLocationIcon) {
            // Check for an internet connection before making the call to Mapbox Directions API
            if (deviceHasInternetConnection()) {
                // Start the call to the Mapbox Directions API
                getInformationFromDirectionsApi(marker.position.latitude,
                        marker.position.longitude, true, null)
            } else {
                Toast.makeText(this, R.string.no_internet_message, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun drawNavigationPolylineRoute(route: DirectionsRoute?) {
        // Check for and remove a previously-drawn navigation route polyline before drawing the new one
        if (mapboxMap!!.polylines.size > 0) {
            mapboxMap!!.removePolyline(mapboxMap!!.polylines[0])
        }

        // Convert LineString coordinates into a LatLng[]
        val lineString = LineString.fromPolyline(route!!.geometry, PRECISION_6)
        val coordinates = lineString.coordinates
        val polylineDirectionsPoints = arrayOfNulls<LatLng>(coordinates.size)
        for (i in coordinates.indices) {
            polylineDirectionsPoints[i] = LatLng(
                    coordinates[i].latitude,
                    coordinates[i].longitude)
        }

        // Draw the navigation route polyline on to the map
        mapboxMap!!.addPolyline(PolylineOptions()
                .add(*polylineDirectionsPoints)
                .color(customThemeManager!!.navigationLineColor)
                .width(NAVIGATION_LINE_WIDTH))
    }

    // Add the mapView's lifecycle to the activity's lifecycle methods
    public override fun onResume() {
        super.onResume()
        mapView!!.onResume()
    }

    override fun onStart() {
        super.onStart()
        mapView!!.onStart()
    }

    override fun onStop() {
        super.onStop()
        mapView!!.onStop()
    }

    public override fun onPause() {
        super.onPause()
        mapView!!.onPause()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        mapView!!.onLowMemory()
    }

    override fun onDestroy() {
        super.onDestroy()
        mapView!!.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        mapView!!.onSaveInstanceState(outState)
    }

    private fun deviceHasInternetConnection(): Boolean {
        var haveConnectedWifi = false
        var haveConnectedMobile = false

        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val netInfo = cm.allNetworkInfo
        for (ni in netInfo) {
            if (ni.typeName.equals("WIFI", ignoreCase = true)) {
                if (ni.isConnected) {
                    haveConnectedWifi = true
                }
            }
            if (ni.typeName.equals("MOBILE", ignoreCase = true)) {
                if (ni.isConnected) {
                    haveConnectedMobile = true
                }
            }
        }
        return haveConnectedWifi || haveConnectedMobile
    }

    /**
     * Custom class which creates marker icons and colors based on the selected theme
     */
    internal inner class CustomThemeManager(private val selectedTheme: Int, private val context: Context,
                                            private val mapView: MapView, private val mapboxMap: MapboxMap) {
        var unselectedMarkerIcon: Icon? = null
        var selectedMarkerIcon: Icon? = null
        var mockLocationIcon: Icon? = null
        var navigationLineColor: Int = 0

        private val BUILDING_EXTRUSION_COLOR = "#c4dbed"
        private val BUILDING_EXTRUSION_OPACITY = .8f

        fun initializeTheme() {
            when (selectedTheme) {
                R.style.AppTheme_Blue -> {
                    mapboxMap.setStyle(getString(R.string.blue_map_style))
                    navigationLineColor = resources.getColor(R.color.navigationRouteLine_blue)
                    unselectedMarkerIcon = IconFactory.getInstance(context).fromResource(R.drawable.blue_unselected_ice_cream)
                    selectedMarkerIcon = IconFactory.getInstance(context).fromResource(R.drawable.blue_selected_ice_cream)
                    mockLocationIcon = IconFactory.getInstance(context).fromResource(R.drawable.blue_user_location)
                    showBuildingExtrusions()
                }
                R.style.AppTheme_Purple -> {
                    mapboxMap.setStyle(getString(R.string.purple_map_style))
                    navigationLineColor = resources.getColor(R.color.navigationRouteLine_purple)
                    unselectedMarkerIcon = IconFactory.getInstance(context).fromResource(R.drawable.purple_unselected_burger)
                    selectedMarkerIcon = IconFactory.getInstance(context).fromResource(R.drawable.purple_selected_burger)
                    mockLocationIcon = IconFactory.getInstance(context).fromResource(R.drawable.purple_user_location)
                }
                R.style.AppTheme_Green -> {
                    mapboxMap.setStyle(getString(R.string.terminal_map_style))
                    navigationLineColor = resources.getColor(R.color.navigationRouteLine_green)
                    unselectedMarkerIcon = IconFactory.getInstance(context).fromResource(R.drawable.green_unselected_money)
                    selectedMarkerIcon = IconFactory.getInstance(context).fromResource(R.drawable.green_selected_money)
                    mockLocationIcon = IconFactory.getInstance(context).fromResource(R.drawable.green_user_location)
                }
                R.style.AppTheme_Neutral -> {
                    mapboxMap.setStyle(Style.MAPBOX_STREETS)
                    navigationLineColor = resources.getColor(R.color.navigationRouteLine_neutral)
                    unselectedMarkerIcon = IconFactory.getInstance(context).fromResource(R.drawable.white_unselected_house)
                    selectedMarkerIcon = IconFactory.getInstance(context).fromResource(R.drawable.gray_selected_house)
                    mockLocationIcon = IconFactory.getInstance(context).fromResource(R.drawable.neutral_orange_user_location)
                }
                R.style.AppTheme_Gray -> {
                    mapboxMap.setStyle(Style.LIGHT);
                    navigationLineColor = getResources().getColor(R.color.navigationRouteLine_gray);
                    unselectedMarkerIcon = IconFactory.getInstance(context).fromResource(R.drawable.white_unselected_bike);
                    selectedMarkerIcon = IconFactory.getInstance(context).fromResource(R.drawable.gray_selected_bike);
                    mockLocationIcon = IconFactory.getInstance(context).fromResource(R.drawable.gray_user_location);
                }
                R.style.AppTheme_PapaBless -> {
                    mapboxMap.setStyle(getString(R.string.papa_bless_map_style))
                    navigationLineColor = resources.getColor(R.color.navigationRouteLine_neutral)
                    unselectedMarkerIcon = IconFactory.getInstance(context).fromResource(R.drawable.marker_papa_bless)
                    selectedMarkerIcon = IconFactory.getInstance(context).fromResource(R.drawable.marker_papa_bless_selected)
                    mockLocationIcon = IconFactory.getInstance(context).fromResource(R.drawable.neutral_orange_user_location)
                }

            }
        }

        private fun showBuildingExtrusions() {
            // Use the Mapbox building plugin to display and customize the opacity/color of building extrusions
            val buildingPlugin = BuildingPlugin(mapView, mapboxMap)
            buildingPlugin.setVisibility(true)
            buildingPlugin.setOpacity(BUILDING_EXTRUSION_OPACITY)
            buildingPlugin.setColor(Color.parseColor(BUILDING_EXTRUSION_COLOR))
        }
    }

    companion object {
        private val LOCKED_MAP_CAMERA_BOUNDS = LatLngBounds.Builder()
            .include(LatLng(51.412056,-57.260742))
            .include(LatLng(12.538749,-148.103516))
            .build()
        private val MOCK_DEVICE_LOCATION_LAT_LNG = LatLng(34.082676, -118.366671)
        private val MAPBOX_LOGO_OPACITY = 75
        private val CAMERA_MOVEMENT_SPEED_IN_MILSECS = 1200
        private val NAVIGATION_LINE_WIDTH = 9f
    }
}
