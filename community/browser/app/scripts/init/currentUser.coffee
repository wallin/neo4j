angular.module('neo4jApp')
.run [
  'NTN'
  'SyncService'
  '$rootScope'
  (NTN, SyncService, $rootScope) ->
    $rootScope.$on 'user:authenticated', (evt, authenticated) ->
      if authenticated
        NTN.ajax('/api/v1/me')
        .then(
          (data) ->
            $rootScope.currentUser = data
            SyncService.sync()
        ,
          ->
            $rootScope.currentUser = undefined
        )
      else
        $rootScope.currentUser = undefined

]
