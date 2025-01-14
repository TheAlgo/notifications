/*
 * Copyright 2021 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 *
 */

package com.amazon.opendistroforelasticsearch.commons.notifications.model

import com.amazon.opendistroforelasticsearch.notifications.util.logger
import org.apache.lucene.search.TotalHits.Relation
import org.apache.lucene.search.TotalHits.Relation.EQUAL_TO
import org.apache.lucene.search.TotalHits.Relation.GREATER_THAN_OR_EQUAL_TO
import org.elasticsearch.action.search.SearchResponse
import org.elasticsearch.common.io.stream.StreamInput
import org.elasticsearch.common.io.stream.StreamOutput
import org.elasticsearch.common.io.stream.Writeable
import org.elasticsearch.common.xcontent.ToXContent.Params
import org.elasticsearch.common.xcontent.XContentBuilder
import org.elasticsearch.common.xcontent.XContentParser
import org.elasticsearch.common.xcontent.XContentParserUtils
import org.elasticsearch.search.SearchHit

abstract class SearchResults<ItemClass : BaseModel> : BaseModel {
    val startIndex: Long
    val totalHits: Long
    val totalHitRelation: Relation
    val objectListFieldName: String
    val objectList: List<ItemClass>

    interface SearchHitParser {
        fun <ItemClass> parse(it: SearchHit): ItemClass
    }

    companion object {
        private val log by logger(SearchResults::class.java)
        private const val START_INDEX_TAG = "startIndex"
        private const val TOTAL_HITS_TAG = "totalHits"
        private const val TOTAL_HIT_RELATION_TAG = "totalHitRelation"
        private fun convertRelation(totalHitRelation: Relation): String {
            return if (totalHitRelation == EQUAL_TO) {
                "eq"
            } else {
                "gte"
            }
        }

        private fun convertRelation(totalHitRelation: String): Relation {
            return if (totalHitRelation == "eq") {
                EQUAL_TO
            } else {
                GREATER_THAN_OR_EQUAL_TO
            }
        }
    }

    constructor(
        objectListFieldName: String,
        objectItem: ItemClass
    ) {
        this.startIndex = 0
        this.totalHits = 1
        this.totalHitRelation = EQUAL_TO
        this.objectListFieldName = objectListFieldName
        this.objectList = listOf(objectItem)
    }

    constructor(
        startIndex: Long,
        totalHits: Long,
        totalHitRelation: Relation,
        objectListFieldName: String,
        objectList: List<ItemClass>
    ) {
        this.startIndex = startIndex
        this.totalHits = totalHits
        this.totalHitRelation = totalHitRelation
        this.objectListFieldName = objectListFieldName
        this.objectList = objectList
    }

    constructor(from: Long, response: SearchResponse, searchHitParser: SearchHitParser, objectListFieldName: String) {
        val mutableList: MutableList<ItemClass> = mutableListOf()
        response.hits.forEach {
            mutableList.add(searchHitParser.parse(it))
        }
        val totalHits = response.hits.totalHits
        val totalHitsVal: Long
        val totalHitsRelation: Relation
        if (totalHits == null) {
            totalHitsVal = mutableList.size.toLong()
            totalHitsRelation = EQUAL_TO
        } else {
            totalHitsVal = totalHits.value
            totalHitsRelation = totalHits.relation
        }
        this.startIndex = from
        this.totalHits = totalHitsVal
        this.totalHitRelation = totalHitsRelation
        this.objectListFieldName = objectListFieldName
        this.objectList = mutableList
    }

    /**
     * Parse the data from parser and create object
     * @param parser data referenced at parser
     */
    constructor(parser: XContentParser, objectListFieldName: String) {
        var startIndex: Long = 0
        var totalHits: Long = 0
        var totalHitRelation: Relation = EQUAL_TO
        var objectList: List<ItemClass>? = null
        XContentParserUtils.ensureExpectedToken(XContentParser.Token.START_OBJECT, parser.currentToken(), parser)
        while (XContentParser.Token.END_OBJECT != parser.nextToken()) {
            val fieldName = parser.currentName()
            parser.nextToken()
            when (fieldName) {
                START_INDEX_TAG -> startIndex = parser.longValue()
                TOTAL_HITS_TAG -> totalHits = parser.longValue()
                TOTAL_HIT_RELATION_TAG -> totalHitRelation = convertRelation(parser.text())
                objectListFieldName -> objectList = parseItemList(parser)
                else -> {
                    parser.skipChildren()
                    log.info("Skipping Unknown field $fieldName")
                }
            }
        }
        objectList ?: throw IllegalArgumentException("$objectListFieldName field absent")
        if (totalHits == 0L) {
            totalHits = objectList.size.toLong()
        }
        this.startIndex = startIndex
        this.totalHits = totalHits
        this.totalHitRelation = totalHitRelation
        this.objectListFieldName = objectListFieldName
        this.objectList = objectList
    }

    /**
     * Parse the item list from parser
     * @param parser data referenced at parser
     * @return created list of items
     */
    private fun parseItemList(parser: XContentParser): List<ItemClass> {
        val retList: MutableList<ItemClass> = mutableListOf()
        XContentParserUtils.ensureExpectedToken(XContentParser.Token.START_ARRAY, parser.currentToken(), parser)
        while (parser.nextToken() != XContentParser.Token.END_ARRAY) {
            retList.add(parseItem(parser))
        }
        return retList
    }

    /**
     * Parse the object item
     * @param parser data referenced at parser
     * @return created item
     */
    abstract fun parseItem(parser: XContentParser): ItemClass

    /**
     * Constructor used in transport action communication.
     * @param input StreamInput stream to deserialize data from.
     * @param reader StreamInput reader class for reading item.
     */
    constructor(input: StreamInput, reader: Writeable.Reader<ItemClass>) : this(
        startIndex = input.readLong(),
        totalHits = input.readLong(),
        totalHitRelation = input.readEnum(Relation::class.java),
        objectListFieldName = input.readString(),
        objectList = input.readList(reader)
    )

    /**
     * {@inheritDoc}
     */
    override fun writeTo(output: StreamOutput) {
        output.writeLong(startIndex)
        output.writeLong(totalHits)
        output.writeEnum(totalHitRelation)
        output.writeString(objectListFieldName)
        output.writeList(objectList)
    }

    /**
     * {@inheritDoc}
     */
    override fun toXContent(builder: XContentBuilder?, params: Params?): XContentBuilder {
        builder!!.startObject()
            .field(START_INDEX_TAG, startIndex)
            .field(TOTAL_HITS_TAG, totalHits)
            .field(TOTAL_HIT_RELATION_TAG, convertRelation(totalHitRelation))
            .startArray(objectListFieldName)
        objectList.forEach { it.toXContent(builder, params) }
        return builder.endArray().endObject()
    }
}
