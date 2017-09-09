package com.mapbox.storelocator.util;

import com.mapbox.services.api.directions.v5.MapboxDirections;

/**
 * Created by shavit on 9/9/17.
 */

public class mDirectionsBuilder {
    public MapboxDirections.Builder Builder(){
        return new MapboxDirections.Builder();
    }
}
