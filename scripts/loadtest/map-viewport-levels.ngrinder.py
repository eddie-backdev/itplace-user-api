# -*- coding: utf-8 -*-
from random import Random
from java.lang import System
from net.grinder.script.Grinder import grinder
from net.grinder.script import Test
from org.ngrinder.http import HTTPRequest, HTTPRequestControl

BASE_URL = "http://localhost:18080"
PREVIEW_LIMIT = 300
# 기본 fixed는 기존 포트폴리오 시나리오를 유지한다. nGrinder param 또는 JVM 옵션으로 선택한다.
VIEWPORT_MODE = System.getProperty(
    "map.viewport.mode", System.getProperty("param", "fixed") or "fixed"
)
RANDOM_SEED = int(System.getProperty("map.viewport.seed", "20260910"))
PAN_DELTA = 0.005
assert VIEWPORT_MODE in ("fixed", "random"), "Unknown viewport mode"

HTTPRequestControl.setConnectionTimeout(60000)
HTTPRequestControl.setSocketTimeout(60000)

CENTERS = [
    (37.5665, 126.9780),
    (37.5796, 126.9770),
    (37.5512, 126.9882),
    (37.5172, 127.0473),
    (37.5400, 126.9500),
]

test1 = Test(1, "map level 4 compact preview 300")
test2 = Test(2, "map level 5 legal-dong cluster")
test3 = Test(3, "map level 7 town cluster")
test4 = Test(4, "map level 10 city cluster")
request = HTTPRequest()


class TestRunner:
    def __init__(self):
        self.base_lat, self.base_lng = CENTERS[grinder.threadNumber % len(CENTERS)]
        self.lat, self.lng = self.base_lat, self.base_lng
        self.lat_offset, self.lng_offset = 0.0, 0.0
        self.random = Random(
            RANDOM_SEED + grinder.processNumber * 1000003 + grinder.threadNumber
        )
        self.scenario = grinder.threadNumber % 4
        test1.record(TestRunner.get_level4_preview)
        test2.record(TestRunner.get_level5_clusters)
        test3.record(TestRunner.get_level7_clusters)
        test4.record(TestRunner.get_level10_clusters)
        grinder.statistics.delayReports = True

    def _get(self, path):
        try:
            response = request.GET(BASE_URL + path, [], [])
            assert response.statusCode == 200, "HTTP %s for %s" % (
                response.statusCode,
                path,
            )
        except BaseException as error:
            grinder.logger.error(
                "REQUEST_FAILED process=%s thread=%s path=%s error=%r"
                % (
                    grinder.processNumber,
                    grinder.threadNumber,
                    path,
                    error,
                )
            )
            raise

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
            (
                "/api/v1/maps/stores/in-view/clusters"
                "?minLat=%s&minLng=%s&maxLat=%s&maxLng=%s&mapLevel=10"
            )
            % (
                33.0 + self.lat_offset,
                124.0 + self.lng_offset,
                39.0 + self.lat_offset,
                130.0 + self.lng_offset,
            )
        )

    def __call__(self):
        if VIEWPORT_MODE == "random":
            # 화면 크기와 기준 지역은 유지하고 매 요청 좌표를 바꿔 exact-key miss를 만든다.
            self.lat_offset = self.random.uniform(-PAN_DELTA, PAN_DELTA)
            self.lng_offset = self.random.uniform(-PAN_DELTA, PAN_DELTA)
            self.lat = self.base_lat + self.lat_offset
            self.lng = self.base_lng + self.lng_offset
        if self.scenario == 0:
            self.get_level4_preview()
        elif self.scenario == 1:
            self.get_level5_clusters()
        elif self.scenario == 2:
            self.get_level7_clusters()
        else:
            self.get_level10_clusters()
