###!
Copyright (c) 2002-2014 "Neo Technology,"
Network Engine for Objects in Lund AB [http://neotechnology.com]

This file is part of Neo4j.

Neo4j is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program.  If not, see <http://www.gnu.org/licenses/>.
###

'use strict';

angular.module('neo4jApp.services')
.service 'SyncService', [
  'localStorageService',
  'NTN'
  '$rootScope'
  (localStorageService, NTN, $rootScope) ->

    setStorageJSON = (response) ->
      for k, v of response
        localStorageService.set(k, v)
      $rootScope.$broadcast 'localStorage:update'

    getStorageJSON = ->
      keys = localStorageService.keys()
      d = {}
      d[k] = localStorageService.get(k) for k in keys
      JSON.stringify(d)

    class SyncService
      sync: ->
        NTN.ajax({
          contentType: 'application/json'
          method: 'PUT'
          url: '/api/v1/store'
          data: getStorageJSON()
        }).then(setStorageJSON)

      resolveWithLocal: ->

      resolveWithServer: ->

      inSync: false
      conflict: false


    new SyncService()
]
