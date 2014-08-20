angular.module('neo4jApp')
.run [
  'NTN'
  'SyncService'
  '$rootScope'
  (NTN, SyncService, $rootScope) ->
    $rootScope.$on 'user:authenticated', (evt, authenticated) ->
      if authenticated
        SyncService.currentUser()
        .then(
          (data) ->
            $rootScope.currentUser = data
        ,
          ->
            $rootScope.currentUser = undefined
        )
      else
        $rootScope.currentUser = undefined

]
