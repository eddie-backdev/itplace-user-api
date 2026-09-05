# -*- coding: utf-8 -*-
from net.grinder.script.Grinder import grinder
from net.grinder.script import Test
from org.ngrinder.http import HTTPRequest, HTTPRequestControl

BASE_URL = "https://userapi.itplace.click"
PREVIEW_LIMIT = 300

HTTPRequestControl.setConnectionTimeout(60000)
HTTPRequestControl.setSocketTimeout(60000)

CENTERS = [
    (37.5665, 126.9780),
    (37.5796, 126.9770),
    (37.5512, 126.9882),
    (37.5172, 127.0473),
    (37.5400, 126.9500),
]

test1 = Test(1, "production map level 4 compact preview 300")
test2 = Test(2, "production map level 5 legal-dong cluster")
test3 = Test(3, "production map level 7 town cluster")
test4 = Test(4, "production map level 10 city cluster")
request = HTTPRequest()


class TestRunner:
    def __init__(self):
        self.lat, self.lng = CENTERS[grinder.threadNumber % len(CENTERS)]
        self.scenario = grinder.threadNumber % 4
        test1.record(TestRunner.get_level4_preview)
        test2.record(TestRunner.get_level5_clusters)
        test3.record(TestRunner.get_level7_clusters)
        test4.record(TestRunner.get_level10_clusters)
        grinder.statistics.delayReports = True

    def _get(self, path):
        response = request.GET(BASE_URL + path, [], [])
        assert response.statusCode == 200, "HTTP %s for %s" % (
            response.statusCode,
            path,
        )

    def get_level4_preview(self):
        self._get(
            (
                "/api/v1/maps/stores/in-view/previews/compact"
                "?minLat=%s&minLng=%s&maxLat=%s&maxLng=%s"
                "&limit=%s"
            )
            % (
                self.lat - 0.015,
                self.lng - 0.020,
                self.lat + 0.015,
                self.lng + 0.020,
                PREVIEW_LIMIT,
            )
        )

    def get_level5_clusters(self):
        self._get(
            (
                "/api/v1/maps/stores/in-view/clusters"
                "?minLat=%s&minLng=%s&maxLat=%s&maxLng=%s&mapLevel=5"
            )
            % (
                self.lat - 0.065,
                self.lng - 0.095,
                self.lat + 0.065,
                self.lng + 0.095,
            )
        )

    def get_level7_clusters(self):
        self._get(
            (
                "/api/v1/maps/stores/in-view/clusters"
                "?minLat=%s&minLng=%s&maxLat=%s&maxLng=%s&mapLevel=7"
            )
            % (
                self.lat - 0.225,
                self.lng - 0.300,
                self.lat + 0.225,
                self.lng + 0.300,
            )
        )

    def get_level10_clusters(self):
        self._get(
            "/api/v1/maps/stores/in-view/clusters"
            "?minLat=33.0&minLng=124.0&maxLat=39.0&maxLng=130.0&mapLevel=10"
        )

    def __call__(self):
        if self.scenario == 0:
            self.get_level4_preview()
        elif self.scenario == 1:
            self.get_level5_clusters()
        elif self.scenario == 2:
            self.get_level7_clusters()
        else:
            self.get_level10_clusters()
