package org.strangeforest.tcb.dataload

import com.google.common.base.*

println 'Loading Tennis Data'
def stopwatch = Stopwatch.createStarted()

def sqlPool = new SqlPool()
def loader = new ATPTennisLoader()
// Live scrapers (atptour.com, Wikipedia) were written against 2022-era page layouts and are likely broken; opt in with -Dtcb.data.scrape=true
def scrape = LoadParams.getBooleanProperty(LoadParams.SCRAPE_PROPERTY, false)

loader.loadPlayers(new PlayerLoader(sqlPool))
sqlPool.withSql { sql -> loader.loadAdditionalPlayerData(sql) }

loader.loadRankings(new RankingLoader(sqlPool))
sqlPool.withSql { sql -> loader.loadAdditionalRankingData(sql) }
if (scrape) {
	sqlPool.withSql { sql -> MissingRankingsLoader.loadRankings(sql) } // fills 1976-77 ranking gaps from atptour.com
	LoadNewRankings.loadRankings(sqlPool)
}

loader.loadMatches(new MatchLoader(sqlPool))
sqlPool.withSql { sql -> loader.loadAdditionalTournamentData(sql) }
if (scrape) LoadNewTournaments.loadTournaments(sqlPool)

sqlPool.withSql { sql -> loader.vacuum(sql) }

sqlPool.withSql { sql -> loader.correctDataFull(sql) }
EloRatingsRunner.computeEloRatings(true)

sqlPool.withSql { sql -> loader.vacuum(sql) }

sqlPool.withSql { sql -> loader.correctData(sql) }
sqlPool.withSql { sql -> loader.refreshMaterializedViews(sql) }

sqlPool.withSql { sql -> loader.vacuum(sql) }

sqlPool.withSql { sql -> new RecordsLoader().loadRecords(loader, sql) }

if (scrape) new WikipediaPlayerDataLoader(sqlPool).updatePlayerData()

sqlPool.withSql { sql -> loader.vacuum(sql) }

println "Tennis Data loaded in $stopwatch"