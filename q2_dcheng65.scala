// Databricks notebook source
// STARTER CODE - DO NOT EDIT THIS CELL
import org.apache.spark.sql.functions.desc
import org.apache.spark.sql.functions._
import org.apache.spark.sql.types._
import spark.implicits._
import org.apache.spark.sql.expressions.Window

// COMMAND ----------

// STARTER CODE - DO NOT EDIT THIS CELL
val customSchema = StructType(Array(StructField("lpep_pickup_datetime", StringType, true), StructField("lpep_dropoff_datetime", StringType, true), StructField("PULocationID", IntegerType, true), StructField("DOLocationID", IntegerType, true), StructField("passenger_count", IntegerType, true), StructField("trip_distance", FloatType, true), StructField("fare_amount", FloatType, true), StructField("payment_type", IntegerType, true)))

// COMMAND ----------

// STARTER CODE - YOU CAN LOAD ANY FILE WITH A SIMILAR SYNTAX.
val df = spark.read
   .format("com.databricks.spark.csv")
   .option("header", "true") // Use first line of all files as header
   .option("nullValue", "null")
   .schema(customSchema)
   .load("/FileStore/tables/nyc_tripdata.csv") // the csv file which you want to work with
   .withColumn("pickup_datetime", from_unixtime(unix_timestamp(col("lpep_pickup_datetime"), "MM/dd/yyyy HH:mm")))
   .withColumn("dropoff_datetime", from_unixtime(unix_timestamp(col("lpep_dropoff_datetime"), "MM/dd/yyyy HH:mm")))
   .drop($"lpep_pickup_datetime")
   .drop($"lpep_dropoff_datetime")

// COMMAND ----------

// LOAD THE "taxi_zone_lookup.csv" FILE SIMILARLY AS ABOVE. CAST ANY COLUMN TO APPROPRIATE DATA TYPE IF NECESSARY.

// ENTER THE CODE BELOW
val taxi_df = spark.read
   .format("com.databricks.spark.csv")
   .option("header", "true") // Use first line of all files as header
   .option("nullValue", "null")
   .load("/FileStore/tables/taxi_zone_lookup.csv")
   .withColumn("LocationID_int", col("LocationID").cast("int"))
   .drop("LocationID").withColumnRenamed("LocationID_int","LocationID")

// COMMAND ----------

// STARTER CODE - DO NOT EDIT THIS CELL
// Some commands that you can use to see your dataframes and results of the operations. You can comment the df.show(5) and uncomment display(df) to see the data differently. You will find these two functions useful in reporting your results.
//display(df)
df.show(5) // view the first 5 rows of the dataframe

// COMMAND ----------

// STARTER CODE - DO NOT EDIT THIS CELL
// Filter the data to only keep the rows where "PULocationID" and the "DOLocationID" are different and the "trip_distance" is strictly greater than 2.0 (>2.0).

// VERY VERY IMPORTANT: ALL THE SUBSEQUENT OPERATIONS MUST BE PERFORMED ON THIS FILTERED DATA

val df_filter = df.filter($"PULocationID" =!= $"DOLocationID" && $"trip_distance" > 2.0)
df_filter.show(5)

// COMMAND ----------

// PART 1a: The top-5 most popular drop locations - "DOLocationID", sorted in descending order - if there is a tie, then one with lower "DOLocationID" gets listed first
// Output Schema: DOLocationID int, number_of_dropoffs int 

// Hint: Checkout the groupBy(), orderBy() and count() functions.

// ENTER THE CODE BELOW
val popular_drop_loc = df_filter.groupBy($"DOLocationID").count().orderBy($"count".desc, $"DOLocationID".asc).withColumnRenamed("count","number_of_dropoffs")
popular_drop_loc.show(5)

// COMMAND ----------

// PART 1b: The top-5 most popular pickup locations - "PULocationID", sorted in descending order - if there is a tie, then one with lower "PULocationID" gets listed first 
// Output Schema: PULocationID int, number_of_pickups int

// Hint: Code is very similar to part 1a above.

// ENTER THE CODE BELOW
val popular_pickup_loc = df_filter.groupBy($"PULocationID").count().orderBy($"count".desc, $"PULocationID".asc).withColumnRenamed("count","number_of_pickups")
popular_pickup_loc.show(5)

// COMMAND ----------

// PART 2: List the top-3 locations with the maximum overall activity, i.e. sum of all pickups and all dropoffs at that LocationID. In case of a tie, the lower LocationID gets listed first.
// Output Schema: LocationID int, number_activities int

// Hint: In order to get the result, you may need to perform a join operation between the two dataframes that you created in earlier parts (to come up with the sum of the number of pickups and dropoffs on each location). 

// ENTER THE CODE BELOW
val activity = popular_drop_loc.join(popular_pickup_loc, popular_drop_loc("DOLocationID")<=>popular_pickup_loc("PULocationID"))
val max_activity = activity.select($"DOLocationID",$"number_of_dropoffs"+$"number_of_pickups").withColumnRenamed("(number_of_dropoffs + number_of_pickups)","number_activities").withColumnRenamed("DOLocationID","LocationID").orderBy($"number_activities".desc,$"LocationID".asc)
max_activity.show(3)

// COMMAND ----------

// PART 3: List all the boroughs in the order of having the highest to lowest number of activities (i.e. sum of all pickups and all dropoffs at that LocationID), along with the total number of activity counts for each borough in NYC during that entire period of time.
// Output Schema: Borough string, total_number_activities int

// Hint: You can use the dataframe obtained from the previous part, and will need to do the join with the 'taxi_zone_lookup' dataframe. Also, checkout the "agg" function applied to a grouped dataframe.

// ENTER THE CODE BELOW
val boroughs = max_activity.join(taxi_df, max_activity("LocationID")<=>taxi_df("LocationID"))
val total_boroughs = boroughs.groupBy($"Borough").agg(sum("number_activities").as("total_number_activities"))
total_boroughs.orderBy($"total_number_activities".desc).show()
//display(boroughs)

// COMMAND ----------

// PART 4: List the top 2 days of week with the largest number of (daily) average pickups, along with the values of average number of pickups on each of the two days. The day of week should be a string with its full name, for example, "Monday" - not a number 1 or "Mon" instead.
// Output Schema: day_of_week string, avg_count float

// Hint: You may need to group by the "date" (without time stamp - time in the day) first. Checkout "to_date" function.

// ENTER THE CODE BELOW
val dates = df_filter.withColumn("date", to_date($"pickup_datetime"))
val date_counts = dates.groupBy($"date").count().withColumn("day_of_week",date_format($"date","EEEE"))
val day_counts = date_counts.groupBy($"day_of_week").agg(avg("count").as("avg_count"))
day_counts.orderBy($"avg_count".desc).show(2)

// COMMAND ----------

// PART 5: For each particular hour of a day (0 to 23, 0 being midnight) - in their order from 0 to 23, find the zone in Brooklyn borough with the LARGEST number of pickups. 
// Output Schema: hour_of_day int, zone string, max_count int

// Hint: You may need to use "Window" over hour of day, along with "group by" to find the MAXIMUM count of pickups

// ENTER THE CODE BELOW
val brooklyn = taxi_df.filter($"Borough" === "Brooklyn")
val hours = df_filter.withColumn("hour_of_day", hour($"pickup_datetime"))
val hour_pickup_count = hours.groupBy($"PULocationID",$"hour_of_day").count().orderBy($"hour_of_day".asc)
val brooklyn_pickup_count = brooklyn.join(hour_pickup_count,brooklyn("LocationID") <=> hour_pickup_count("PULocationID"))
//val zone_count = brooklyn_pickup_count.groupBy($"hour_of_day",$"Zone").agg(max(count).as("max_count")).orderBy($"hour_of_day".asc)
val hour_count = brooklyn_pickup_count.groupBy($"hour_of_day").agg(max("count").as("max_count"))
val hour_zone_count = hour_count.join(brooklyn_pickup_count, hour_count("hour_of_day")<=>brooklyn_pickup_count("hour_of_day") && hour_count("max_count") <=> brooklyn_pickup_count("count")).drop(brooklyn_pickup_count("hour_of_day"))
val ordered_hour_zone_count = hour_zone_count.select($"hour_of_day",$"Zone",$"max_count").orderBy($"hour_of_day")
//brooklyn.show()
display(ordered_hour_zone_count)

// COMMAND ----------

// PART 6 - Find which 3 different days of the January, in Manhattan, saw the largest percentage increment in pickups compared to previous day, in the order from largest increment % to smallest increment %. 
// Print the day of month along with the percent CHANGE (can be negative), rounded to 2 decimal places, in number of pickups compared to previous day.
// Output Schema: day int, percent_change float


// Hint: You might need to use lag function, over a window ordered by day of month.

// ENTER THE CODE BELOW
val manhattan = taxi_df.filter($"Borough" === "Manhattan").join(df_filter, df_filter("PULocationID") <=> taxi_df("LocationID"))
val jan_manhattan = manhattan.withColumn("month",month($"pickup_datetime")).filter($"month"===1)
                             .withColumn("year",year($"pickup_datetime")).filter($"year"===2019)
                             .withColumn("day",dayofmonth($"pickup_datetime"))
val day_pickups = jan_manhattan.groupBy($"day").count().orderBy($"day".asc)

val w = Window.orderBy("day")
val prev_count = lag(col("count"), 1).over(w)
val day_change = day_pickups.withColumn("prev_count", prev_count)
val percent_change = day_change.withColumn("percent_change", round((($"count" - $"prev_count")/$"prev_count")*100,2))
val order_percent_change = percent_change.orderBy($"percent_change".desc)
//display(percent_change)
order_percent_change.select($"day",$"percent_change").show(3)
